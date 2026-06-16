package com.example.llmshadow.queue;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.example.llmshadow.dto.LlmProxyRequest;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shadow.queue.backend", havingValue = "redis", matchIfMissing = true)
public class RedisShadowJobQueue implements ShadowJobQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisShadowJobQueue.class);
    private static final String PAYLOAD_FIELD = "payload";
    private static final String REASON_FIELD = "reason";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamKey;
    private final String group;
    private final String consumer;
    private final String deadLetterStream;
    private final String retryZset;
    private final long readTimeoutMs;
    private final Duration pendingIdle;

    public RedisShadowJobQueue(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ShadowProperties shadowProperties) {
        ShadowProperties.Queue queueProperties = shadowProperties.queue();
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamKey = queueProperties.streamKey();
        this.group = queueProperties.group();
        this.consumer = queueProperties.consumer();
        this.deadLetterStream = queueProperties.deadLetterStream();
        this.retryZset = queueProperties.retryZset();
        this.readTimeoutMs = queueProperties.readTimeoutMs().toMillis();
        this.pendingIdle = queueProperties.pendingIdleMs();
    }

    @PostConstruct
    void initializeGroup() {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                createConsumerGroup(connection);
                return null;
            });
        } catch (RuntimeException ex) {
            log.warn("event=redis_queue_unavailable message={}", safeMessage(ex));
        }
    }

    @Override
    public void publish(ShadowComparisonJob job) {
        addToStream(new RedisShadowJobPayload(
                job.requestId(),
                job.request(),
                job.primaryRawResponse(),
                job.createdAt(),
                0));
    }

    @Override
    public void moveDueRetriesToQueue(int batchSize) {
        long now = Instant.now().toEpochMilli();
        Set<String> duePayloads = redisTemplate.opsForZSet().rangeByScore(retryZset, 0, now, 0, batchSize);
        if (duePayloads == null || duePayloads.isEmpty()) {
            return;
        }

        for (String payloadJson : duePayloads) {
            redisTemplate.opsForStream().add(streamKey, Map.of(PAYLOAD_FIELD, payloadJson));
            redisTemplate.opsForZSet().remove(retryZset, payloadJson);
        }
    }

    @Override
    public List<QueuedShadowJob> recoverStalePending(int batchSize) {
        return redisTemplate.execute((RedisCallback<List<QueuedShadowJob>>) connection -> {
            byte[] rawKey = streamKey.getBytes(StandardCharsets.UTF_8);
            RedisStreamCommands commands = connection.streamCommands();
            PendingMessages pendingMessages = commands.xPending(
                    rawKey,
                    group,
                    XPendingOptions.unbounded((long) batchSize));

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return List.of();
            }

            List<RecordId> staleIds = pendingMessages.stream()
                    .filter(this::isStale)
                    .map(PendingMessage::getId)
                    .toList();
            if (staleIds.isEmpty()) {
                return List.of();
            }

            List<ByteRecord> claimed = commands.xClaim(
                    rawKey,
                    group,
                    consumer,
                    XClaimOptions.minIdle(pendingIdle).ids(staleIds));

            if (claimed == null || claimed.isEmpty()) {
                return List.of();
            }

            return claimed.stream()
                    .map(this::toQueuedJob)
                    .toList();
        });
    }

    @Override
    public List<QueuedShadowJob> poll(int batchSize) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(readTimeoutMs)),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return List.of();
        }

        return records.stream()
                .map(this::toQueuedJob)
                .toList();
    }

    @Override
    public void acknowledge(QueuedShadowJob job) {
        redisTemplate.opsForStream().acknowledge(streamKey, group, RecordId.of(job.messageId()));
    }

    @Override
    public void retry(QueuedShadowJob job, int nextAttempt, Instant runAt, String reason) {
        RedisShadowJobPayload payload = new RedisShadowJobPayload(
                job.requestId(),
                job.request(),
                job.primaryRawResponse(),
                job.createdAt(),
                nextAttempt);
        redisTemplate.opsForZSet().add(retryZset, writePayload(payload), runAt.toEpochMilli());
        acknowledge(job);
    }

    @Override
    public void deadLetter(QueuedShadowJob job, String reason) {
        RedisShadowJobPayload payload = new RedisShadowJobPayload(
                job.requestId(),
                job.request(),
                job.primaryRawResponse(),
                job.createdAt(),
                job.attempts());
        redisTemplate.opsForStream().add(deadLetterStream, Map.of(
                PAYLOAD_FIELD, writePayload(payload),
                REASON_FIELD, reason));
        acknowledge(job);
    }

    @Override
    public void clear() {
        redisTemplate.delete(List.of(streamKey, retryZset, deadLetterStream));
        initializeGroup();
    }

    @Override
    public long queuedCount() {
        Long count = redisTemplate.opsForStream().size(streamKey);
        return count == null ? 0 : count;
    }

    @Override
    public long retryCount() {
        Long count = redisTemplate.opsForZSet().zCard(retryZset);
        return count == null ? 0 : count;
    }

    @Override
    public long deadLetterCount() {
        Long count = redisTemplate.opsForStream().size(deadLetterStream);
        return count == null ? 0 : count;
    }

    private void addToStream(RedisShadowJobPayload payload) {
        redisTemplate.opsForStream().add(streamKey, Map.of(PAYLOAD_FIELD, writePayload(payload)));
    }

    private QueuedShadowJob toQueuedJob(MapRecord<String, Object, Object> record) {
        Object payloadValue = record.getValue().get(PAYLOAD_FIELD);
        RedisShadowJobPayload payload = readPayload(String.valueOf(payloadValue));
        return new QueuedShadowJob(
                record.getId().getValue(),
                payload.requestId(),
                payload.request(),
                payload.primaryRawResponse(),
                payload.createdAt(),
                payload.attempts());
    }

    private QueuedShadowJob toQueuedJob(ByteRecord record) {
        byte[] payloadValue = payloadValue(record);
        RedisShadowJobPayload payload = readPayload(new String(payloadValue, StandardCharsets.UTF_8));
        return new QueuedShadowJob(
                record.getId().getValue(),
                payload.requestId(),
                payload.request(),
                payload.primaryRawResponse(),
                payload.createdAt(),
                payload.attempts());
    }

    private byte[] payloadValue(ByteRecord record) {
        return record.getValue().entrySet().stream()
                .filter(entry -> PAYLOAD_FIELD.equals(new String(entry.getKey(), StandardCharsets.UTF_8)))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new IllegalStateException("Redis stream record is missing payload"));
    }

    private boolean isStale(PendingMessage pendingMessage) {
        return pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(pendingIdle) >= 0;
    }

    private String writePayload(RedisShadowJobPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize shadow queue payload", ex);
        }
    }

    private RedisShadowJobPayload readPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, RedisShadowJobPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not deserialize shadow queue payload", ex);
        }
    }

    private void createConsumerGroup(RedisConnection connection) {
        byte[] rawKey = streamKey.getBytes(StandardCharsets.UTF_8);
        try {
            RedisStreamCommands commands = connection.streamCommands();
            commands.xGroupCreate(rawKey, group, ReadOffset.from("0-0"), true);
        } catch (RedisSystemException ex) {
            if (!safeMessage(ex).contains("BUSYGROUP")) {
                throw ex;
            }
        }
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record RedisShadowJobPayload(
            String requestId,
            LlmProxyRequest request,
            String primaryRawResponse,
            Instant createdAt,
            int attempts) {
    }
}
