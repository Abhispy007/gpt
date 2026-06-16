package com.example.llmshadow.persistence;

import com.example.llmshadow.dto.ShadowComparisonJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShadowOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ShadowOutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(ShadowComparisonJob job, String reason) {
        jdbcTemplate.update("""
                INSERT INTO shadow_outbox (request_id, payload_json, reason, created_at)
                VALUES (?, ?, ?, ?)
                """, job.requestId(), write(job), reason, Instant.now().toString());
    }

    public List<ShadowOutboxRecord> findBatch(int limit) {
        return jdbcTemplate.query("""
                SELECT id, payload_json
                FROM shadow_outbox
                ORDER BY id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new ShadowOutboxRecord(
                        rs.getLong("id"),
                        read(rs.getString("payload_json"))),
                limit);
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM shadow_outbox WHERE id = ?", id);
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shadow_outbox", Long.class);
        return count == null ? 0 : count;
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM shadow_outbox");
    }

    private String write(ShadowComparisonJob job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize shadow outbox payload", ex);
        }
    }

    private ShadowComparisonJob read(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, ShadowComparisonJob.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not deserialize shadow outbox payload", ex);
        }
    }
}
