package tech.ydb.export;

import java.util.concurrent.CompletableFuture;

import javax.annotation.WillNotClose;

import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.operation.Operation;
import tech.ydb.export.impl.ExportClientImpl;
import tech.ydb.export.impl.GrpcExportRpcImpl;
import tech.ydb.export.result.ExportToS3Result;
import tech.ydb.export.result.ExportToYtResult;
import tech.ydb.export.settings.ExportToS3Settings;
import tech.ydb.export.settings.ExportToYtSettings;

/**
 * @author Kirill Kurdyukov
 */
public interface ExportClient {

    static ExportClient newClient(@WillNotClose GrpcTransport transport) {
        return new ExportClientImpl(GrpcExportRpcImpl.useTransport(transport));
    }

    CompletableFuture<Operation<ExportToS3Result>> exportS3(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            ExportToS3Settings exportToS3Settings
    );

    CompletableFuture<Operation<ExportToYtResult>> exportYt(
            String host,
            String token,
            ExportToYtSettings exportToYtSettings
    );
}
