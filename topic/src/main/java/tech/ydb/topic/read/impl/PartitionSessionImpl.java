package tech.ydb.topic.read.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.ydb.core.utils.ProtobufUtils;
import tech.ydb.proto.topic.YdbTopic;
import tech.ydb.topic.description.Codec;
import tech.ydb.topic.read.Message;
import tech.ydb.topic.read.OffsetsRange;
import tech.ydb.topic.read.PartitionSession;
import tech.ydb.topic.read.events.DataReceivedEvent;
import tech.ydb.topic.read.impl.events.DataReceivedEventImpl;
import tech.ydb.topic.utils.Encoder;

/**
 * @author Nikolay Perfilov
 */
public class PartitionSessionImpl {
    private static final Logger logger = LoggerFactory.getLogger(PartitionSessionImpl.class);

    private final long id;
    private final String path;
    private final long partitionId;
    private final PartitionSession sessionInfo;
    private final OffsetsRange partitionOffsets;
    private final Executor decompressionExecutor;
    private final AtomicBoolean isWorking = new AtomicBoolean(true);

    private final Queue<Batch> decodingBatches = new LinkedList<>();
    private final Queue<Batch> readingQueue = new ConcurrentLinkedQueue<>();
    private final Function<DataReceivedEvent, CompletableFuture<Void>> dataEventCallback;
    private final AtomicBoolean isReadingNow = new AtomicBoolean();
    private final Consumer<List<OffsetsRange>> commitFunction;
    private final NavigableMap<Long, CompletableFuture<Void>> commitFutures = new ConcurrentSkipListMap<>();
    // Offset of the last read message + 1
    private long lastReadOffset;
    private long lastCommittedOffset;

    private PartitionSessionImpl(Builder builder) {
        this.id = builder.id;
        this.path = builder.path;
        this.partitionId = builder.partitionId;
        this.sessionInfo = new PartitionSession(id, partitionId, path);
        this.lastReadOffset = builder.committedOffset;
        this.lastCommittedOffset = builder.committedOffset;
        this.partitionOffsets = builder.partitionOffsets;
        this.decompressionExecutor = builder.decompressionExecutor;
        this.dataEventCallback = builder.dataEventCallback;
        this.commitFunction = builder.commitFunction;
        logger.info("[{}] Partition session {} (partition {}) is started. CommittedOffset: {}. " +
                "Partition offsets: {}-{}", path, id, partitionId, lastReadOffset, partitionOffsets.getStart(),
                partitionOffsets.getEnd());
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public long getId() {
        return id;
    }

    public long getPartitionId() {
        return partitionId;
    }

    public String getPath() {
        return path;
    }

    public PartitionSession getSessionInfo() {
        return sessionInfo;
    }

    public CompletableFuture<Void> addBatches(List<YdbTopic.StreamReadMessage.ReadResponse.Batch> batches) {
        if (!isWorking.get()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> batchFutures = new LinkedList<>();
        batches.forEach(batch -> {
            BatchMeta batchMeta = new BatchMeta(batch);
            Batch newBatch = new Batch(batchMeta);
            List<YdbTopic.StreamReadMessage.ReadResponse.MessageData> batchMessages = batch.getMessageDataList();
            if (!batchMessages.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[{}] Received a batch of {} messages (offsets {} - {}) for partition session {} " +
                                    "(partition {})", path, batchMessages.size(), batchMessages.get(0).getOffset(),
                            batchMessages.get(batchMessages.size() - 1).getOffset(), id, partitionId);
                }
            } else {
                logger.error("[{}] Received empty batch for partition session {} (partition {}). This shouldn't happen",
                        path, id, partitionId);
            }
            batchMessages.forEach(messageData -> {
                long commitOffsetFrom = lastReadOffset;
                long messageOffset = messageData.getOffset();
                long newReadOffset = messageOffset + 1;
                if (newReadOffset > lastReadOffset) {
                    lastReadOffset = newReadOffset;
                    if (logger.isTraceEnabled()) {
                        logger.trace("[{}] Received a message with offset {} for partition session {} " +
                                        "(partition {}). lastReadOffset is now {}", path, messageOffset, id,
                                partitionId, lastReadOffset);
                    }
                } else {
                    logger.error("[{}] Received a message with offset {} which is less than last read offset {} " +
                                    "for partition session {} (partition {})", path, messageOffset, lastReadOffset, id,
                            partitionId);
                }
                newBatch.addMessage(new MessageImpl.Builder()
                        .setBatchMeta(batchMeta)
                        .setPartitionSession(this)
                        .setData(messageData.getData().toByteArray())
                        .setOffset(messageOffset)
                        .setSeqNo(messageData.getSeqNo())
                        .setCommitOffsetFrom(commitOffsetFrom)
                        .setCreatedAt(ProtobufUtils.protoToInstant(messageData.getCreatedAt()))
                        .setMessageGroupId(messageData.getMessageGroupId())
                        .build()
                );
            });
            batchFutures.add(newBatch.getReadFuture());
            synchronized (decodingBatches) {
                decodingBatches.add(newBatch);
            }

            CompletableFuture.runAsync(() -> decode(newBatch), decompressionExecutor)
                    .thenRun(() -> {
                        boolean haveNewBatchesReady = false;
                        synchronized (decodingBatches) {
                            // Taking all encoded messages to sending queue
                            while (true) {
                                Batch decodingBatch = decodingBatches.peek();
                                if (decodingBatch != null
                                        && (decodingBatch.isDecompressed() || decodingBatch.getCodec() == Codec.RAW)) {
                                    decodingBatches.remove();
                                    if (logger.isTraceEnabled()) {
                                        List<MessageImpl> messages = decodingBatch.getMessages();
                                        logger.trace("[{}] Adding batch with offsets {}-{} to reading queue of " +
                                                        "partition session {} (partition {})", path,
                                                messages.get(0).getOffset(),
                                                messages.get(messages.size() - 1).getOffset(), id, partitionId);
                                    }
                                    readingQueue.add(decodingBatch);
                                    haveNewBatchesReady = true;
                                } else {
                                    break;
                                }
                            }
                        }
                        if (haveNewBatchesReady) {
                            sendDataToReadersIfNeeded();
                        }
                    });
        });
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture<?>[0]));
    }

    // Сommit single offset range with result future
    public CompletableFuture<Void> commitOffsetRange(OffsetsRange rangeToCommit) {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        synchronized (commitFutures) {
            if (isWorking.get()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[{}] Offset range [{}, {}) is requested to be committed for partition session {} " +
                                    "(partition {}). Last committed offset is {} (commit lag is {})", path,
                            rangeToCommit.getStart(), rangeToCommit.getEnd(), id, partitionId, lastCommittedOffset,
                            rangeToCommit.getStart() - lastCommittedOffset);
                }
                commitFutures.put(rangeToCommit.getEnd(), resultFuture);
            } else {
                logger.info("[{}] Offset range [{}, {}) is requested to be committed, but partition session {} " +
                        "(partition {}) is already closed", path, rangeToCommit.getStart(), rangeToCommit.getEnd(), id,
                        partitionId);
                resultFuture.completeExceptionally(new RuntimeException("Partition session " + id + " (partition " +
                        partitionId + ") for " + path + " is already closed"));
                return resultFuture;
            }
        }
        List<OffsetsRange> rangeWrapper = new ArrayList<>(1);
        rangeWrapper.add(rangeToCommit);
        commitFunction.accept(rangeWrapper);
        return resultFuture;
    }

    // Bulk commit without result future
    public void commitOffsetRanges(List<OffsetsRange> rangesToCommit) {
        if (isWorking.get()) {
            if (logger.isDebugEnabled()) {
                StringBuilder message = new StringBuilder("[").append(path)
                        .append("] Sending CommitRequest for partition session ").append(id)
                        .append(" (partition ").append(partitionId).append(") with offset ranges ");
                addRangesToString(message, rangesToCommit);
                logger.info(message.toString());
            }
            commitFunction.accept(rangesToCommit);
        } else if (logger.isInfoEnabled()) {
            StringBuilder message = new StringBuilder("[").append(path).append("] Offset ranges ");
            addRangesToString(message, rangesToCommit);
            message.append(" are requested to be committed, but partition session ").append(id)
                    .append(" (partition ").append(partitionId).append(") is already closed");
            logger.info(message.toString());
        }
    }

    private static void addRangesToString(StringBuilder stringBuilder, List<OffsetsRange> ranges) {
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                stringBuilder.append(", ");
            }
            OffsetsRange range = ranges.get(i);
            stringBuilder.append("[").append(range.getStart()).append(",").append(range.getEnd()).append(")");
        }
    }

    public void handleCommitResponse(long committedOffset) {
        if (committedOffset <= lastCommittedOffset) {
            logger.error("[{}] Commit response received for partition session {} (partition {}). Committed offset: {}" +
                            " which is not greater than previous committed offset: {}.", path, id, partitionId,
                    committedOffset, lastCommittedOffset);
            return;
        }
        Map<Long, CompletableFuture<Void>> futuresToComplete = commitFutures.headMap(committedOffset, true);
        logger.info("[{}] Commit response received for partition session {} (partition {}). Committed offset: {}" +
                        ". Previous committed offset: {} (diff is {} message(s)). Completing {} commit futures",
                path, id, partitionId, committedOffset, lastCommittedOffset, committedOffset - lastCommittedOffset,
                futuresToComplete.size());
        lastCommittedOffset = committedOffset;
        futuresToComplete.values().forEach(future -> future.complete(null));
        futuresToComplete.clear();
    }

    private void decode(Batch batch) {
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Started decoding batch for partition session {} (partition {})", path, id, partitionId);
        }
        if (batch.getCodec() == Codec.RAW) {
            return;
        }

        batch.getMessages().forEach(message -> {
            try {
                message.setData(Encoder.decode(batch.getCodec(), message.getData()));
                message.setDecompressed(true);
            } catch (IOException exception) {
                message.setException(exception);
                logger.info("[{}] Exception was thrown while decoding a message in partition session {} " +
                        "(partition {})", path, id, partitionId);
            }
        });
        batch.setDecompressed(true);

        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Finished decoding batch for partition session {} (partition {})", path, id, partitionId);
        }
    }

    private void sendDataToReadersIfNeeded() {
        if (!isWorking.get()) {
            return;
        }
        if (isReadingNow.compareAndSet(false, true)) {
            Batch batchToRead = readingQueue.poll();
            if (batchToRead == null) {
                isReadingNow.set(false);
                return;
            }
            // Should be called maximum in 1 thread at a time
            List<MessageImpl> messageImplList = batchToRead.getMessages();
            List<Message> messagesToRead = new ArrayList<>(messageImplList);
            OffsetsRange offsetsToCommit = new OffsetsRangeImpl(messageImplList.get(0).getCommitOffsetFrom(),
                    messageImplList.get(messageImplList.size() - 1).getOffset() + 1);
            DataReceivedEvent event = new DataReceivedEventImpl(this, messagesToRead, offsetsToCommit);
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] DataReceivedEvent callback with {} message(s) (offsets {}-{}) for partition " +
                                "session {} " + "(partition {}) is about to be called...", path, messagesToRead.size(),
                        messagesToRead.get(0).getOffset(), messagesToRead.get(messagesToRead.size() - 1).getOffset(),
                        id, partitionId);
            }
            dataEventCallback.apply(event)
                    .whenComplete((res, th) -> {
                        if (th != null) {
                            logger.error("[{}] DataReceivedEvent callback with {} message(s) (offsets {}-{}) for " +
                                    "partition session {} (partition {}) finished with error: ", path,
                                    messagesToRead.size(), messagesToRead.get(0).getOffset(),
                                    messagesToRead.get(messagesToRead.size() - 1).getOffset(), id, partitionId, th);
                        } else if (logger.isDebugEnabled()) {
                            logger.debug("[{}] DataReceivedEvent callback with {} message(s) (offsets {}-{}) for " +
                                    "partition session {} (partition {}) successfully finished", path,
                                    messagesToRead.size(), messagesToRead.get(0).getOffset(),
                                    messagesToRead.get(messagesToRead.size() - 1).getOffset(), id, partitionId);
                        }
                        isReadingNow.set(false);
                        batchToRead.complete();
                        sendDataToReadersIfNeeded();
                    });
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace("[{}] Partition session {} (partition {}) - no need to send data to readers: " +
                        "reading is already being performed", path, id, partitionId);
            }
        }
    }

    public void shutdown() {
        synchronized (commitFutures) {
            isWorking.set(false);
            logger.info("[{}] Partition session {} (partition {}) is shutting down. Failing {} commit futures...", path,
                    id, partitionId, commitFutures.size());
            commitFutures.values().forEach(f -> f.completeExceptionally(new RuntimeException("Partition session " + id +
                    " (partition " + partitionId + ") for " + path + " is closed")));
        }
        synchronized (decodingBatches) {
            decodingBatches.forEach(Batch::complete);
            readingQueue.forEach(Batch::complete);
        }
    }

    /**
     * BUILDER
     */
    public static class Builder {
        private long id;
        private String path;
        private long partitionId;
        private long committedOffset;
        private OffsetsRange partitionOffsets;
        private Executor decompressionExecutor;
        private Function<DataReceivedEvent, CompletableFuture<Void>> dataEventCallback;
        private Consumer<List<OffsetsRange>> commitFunction;

        public Builder setId(long id) {
            this.id = id;
            return this;
        }

        public Builder setPath(String path) {
            this.path = path;
            return this;
        }

        public Builder setPartitionId(long partitionId) {
            this.partitionId = partitionId;
            return this;
        }

        public Builder setCommittedOffset(long committedOffset) {
            this.committedOffset = committedOffset;
            return this;
        }

        public Builder setPartitionOffsets(OffsetsRange partitionOffsets) {
            this.partitionOffsets = partitionOffsets;
            return this;
        }

        public Builder setDecompressionExecutor(Executor decompressionExecutor) {
            this.decompressionExecutor = decompressionExecutor;
            return this;
        }

        public Builder setDataEventCallback(Function<DataReceivedEvent, CompletableFuture<Void>> dataEventCallback) {
            this.dataEventCallback = dataEventCallback;
            return this;
        }

        public Builder setCommitFunction(Consumer<List<OffsetsRange>> commitFunction) {
            this.commitFunction = commitFunction;
            return this;
        }

        public PartitionSessionImpl build() {
            return new PartitionSessionImpl(this);
        }
    }
}
