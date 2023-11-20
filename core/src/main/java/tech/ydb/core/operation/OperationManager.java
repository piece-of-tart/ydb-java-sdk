package tech.ydb.core.operation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.ydb.core.Issue;
import tech.ydb.core.Result;
import tech.ydb.core.Status;
import tech.ydb.core.StatusCode;
import tech.ydb.core.grpc.GrpcRequestSettings;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.proto.OperationProtos;
import tech.ydb.proto.operation.v1.OperationServiceGrpc;

/**
 * @author Kirill Kurdyukov
 */
public final class OperationManager {

    private static final Logger logger = LoggerFactory.getLogger(OperationManager.class);
    private static final Status ASYNC_ARE_UNSUPPORTED = Status.of(StatusCode.CLIENT_INTERNAL_ERROR)
            .withIssues(Issue.of("Async operations are not supported", Issue.Severity.ERROR));
    private static final long OPERATION_CHECK_TIMEOUT_MS = 1_000;

    private final GrpcTransport grpcTransport;
    private final ScheduledExecutorService scheduledExecutorService;
    private final GrpcRequestSettings requestSettings = GrpcRequestSettings.newBuilder().build();

    public OperationManager(GrpcTransport grpcTransport) {
        this.grpcTransport = grpcTransport;
        this.scheduledExecutorService = grpcTransport.getScheduler();
    }

    @VisibleForTesting
    static Status status(OperationProtos.Operation operation) {
        StatusCode code = StatusCode.fromProto(operation.getStatus());
        Double consumedRu = null;
        if (operation.hasCostInfo()) {
            consumedRu = operation.getCostInfo().getConsumedUnits();
        }

        return Status.of(code, consumedRu, Issue.fromPb(operation.getIssuesList()));
    }

    public static <R, M extends Message> Function<Result<R>, Result<M>> syncResultUnwrapper(
            Function<R, OperationProtos.Operation> operationExtractor,
            Class<M> resultClass
    ) {
        return (result) -> {
            if (!result.isSuccess()) {
                return result.map(null);
            }
            OperationProtos.Operation operation = operationExtractor.apply(result.getValue());
            if (operation.getReady()) {
                Status status = status(operation);
                if (!status.isSuccess()) {
                    return Result.fail(status);
                }

                try {
                    M resultMessage = operation.getResult().unpack(resultClass);
                    return Result.success(resultMessage, status);
                } catch (InvalidProtocolBufferException ex) {
                    return Result.error("Can't unpack message " + resultClass.getName(), ex);
                }
            }
            return Result.fail(ASYNC_ARE_UNSUPPORTED);
        };
    }

    public static <R> Function<Result<R>, Status> syncStatusUnwrapper(
            Function<R, OperationProtos.Operation> operationExtractor
    ) {
        return (result) -> {
            if (!result.isSuccess()) {
                return result.getStatus();
            }

            OperationProtos.Operation operation = operationExtractor.apply(result.getValue());
            if (operation.getReady()) {
                return status(operation);
            }

            return ASYNC_ARE_UNSUPPORTED;
        };
    }

    public <R, M extends Message> Function<Result<R>, Operation<M>> operationUnwrapper(
            Function<R, OperationProtos.Operation> operationExtractor,
            Class<M> resultClass
    ) {
        return (result) -> {
            if (!result.isSuccess()) {
                return new Operation<>(
                        null,
                        null,
                        CompletableFuture.completedFuture(result.map(null))
                );
            }

            OperationProtos.Operation operationProto = operationExtractor.apply(result.getValue());

            Operation<M> operation = new Operation<>(operationProto.getId(), this, new CompletableFuture<>());

            completeOperation(operationProto, operation, resultClass);

            return operation;
        };
    }

    private <V extends Message> void completeOperation(
            final OperationProtos.Operation operationProto,
            final Operation<V> operation,
            final Class<V> resultClass
    ) {
        if (operation.getResultFuture().isDone()) {
            return;
        }

        final Status status = status(operationProto);

        if (operationProto.getReady()) {
            if (status.isSuccess()) {
                try {
                    V unpackResult = operationProto.getResult().unpack(resultClass);

                    operation.getResultFuture().complete(Result.success(unpackResult, status));
                } catch (InvalidProtocolBufferException ex) {
                    operation.getResultFuture().completeExceptionally(ex);
                }
            } else {
                operation.getResultFuture().complete(Result.fail(status));
            }

            return;
        }

        scheduledExecutorService.schedule(
                () -> {
                    assert operation.getOperationId() != null;
                    if (operation.getResultFuture().isDone()) {
                        return;
                    }

                    OperationProtos.GetOperationRequest request = OperationProtos.GetOperationRequest
                            .newBuilder()
                            .setId(operation.getOperationId())
                            .build();

                    grpcTransport.unaryCall(
                            OperationServiceGrpc.getGetOperationMethod(),
                            requestSettings,
                            request
                    ).whenComplete(
                            (getOperationResponseResult, throwable) -> {
                                if (throwable != null) {
                                    operation.getResultFuture().completeExceptionally(throwable);
                                } else if (getOperationResponseResult != null) {
                                    if (getOperationResponseResult.isSuccess()) {
                                        completeOperation(
                                                getOperationResponseResult.getValue().getOperation(),
                                                operation,
                                                resultClass
                                        );
                                    } else {
                                        operation.getResultFuture().complete(
                                                getOperationResponseResult.map(null)
                                        );
                                    }
                                }
                            }
                    );
                },
                OPERATION_CHECK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        );
    }

    CompletableFuture<Result<OperationProtos.CancelOperationResponse>> cancel(
            final Operation<?> operation
    ) {
        assert operation.getOperationId() != null;
        return grpcTransport.unaryCall(
                OperationServiceGrpc.getCancelOperationMethod(),
                GrpcRequestSettings.newBuilder()
                        .build(),
                OperationProtos.CancelOperationRequest.newBuilder()
                        .setId(operation.getOperationId())
                        .build()
        ).whenComplete(
                (cancelOperationResponseResult, throwable) -> {
                    if (throwable != null) {
                        logger.error("Fail cancel polling operation with id: {}",
                                operation.getOperationId(), throwable);
                    }

                    if (cancelOperationResponseResult.isSuccess()) {
                        logger.info("Success cancel polling operation with id: {}", operation.getOperationId());

                        operation.getResultFuture().complete(Result.fail(Status.of(StatusCode.CANCELLED)));
                    } else {
                        logger.error("Fail cancel polling operation with id: {}", operation.getOperationId());
                    }
                }
        );
    }
}
