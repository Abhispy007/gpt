package com.example.llmshadow.logging;

import com.example.llmshadow.service.RedactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MismatchLogger {

    private static final Logger log = LoggerFactory.getLogger(MismatchLogger.class);

    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;

    public MismatchLogger(ObjectMapper objectMapper, RedactionService redactionService) {
        this.objectMapper = objectMapper;
        this.redactionService = redactionService;
    }

    public void logMismatch(
            String requestId,
            JsonNode primaryJson,
            JsonNode candidateJson,
            String primaryHash,
            String candidateHash) {
        log.warn(
                "event=llm_shadow_mismatch requestId={} primaryHash={} candidateHash={} primaryJson={} candidateJson={}",
                requestId,
                primaryHash,
                candidateHash,
                compact(redactionService.redact(primaryJson)),
                compact(redactionService.redact(candidateJson)));
    }

    public void logShadowError(String requestId, String errorType, String message) {
        log.warn(
                "event=shadow_error requestId={} errorType={} message={}",
                requestId,
                errorType,
                message);
    }

    public void logJsonExtractionError(String requestId, String source, String message) {
        log.warn(
                "event=json_extraction_error requestId={} source={} message={}",
                requestId,
                source,
                message);
    }

    public void logShadowDropped(String requestId, String message) {
        log.warn("event=shadow_dropped requestId={} message={}", requestId, message);
    }

    public void logDurableJobQueued(String requestId) {
        log.info("event=shadow_job_queued requestId={}", requestId);
    }

    public void logShadowOutboxed(String requestId, String reason) {
        log.warn("event=shadow_job_outboxed requestId={} reason={}", requestId, reason);
    }

    public void logShadowOutboxReplayed(String requestId) {
        log.info("event=shadow_outbox_replayed requestId={}", requestId);
    }

    public void logShadowDeadLettered(String requestId, String reason) {
        log.warn("event=shadow_dead_lettered requestId={} reason={}", requestId, reason);
    }

    public void logQueueError(String errorType, String message) {
        log.warn("event=shadow_queue_error errorType={} message={}", errorType, message);
    }

    private String compact(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ex) {
            return String.valueOf(jsonNode);
        }
    }
}
