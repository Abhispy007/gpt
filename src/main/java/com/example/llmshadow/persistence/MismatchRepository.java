package com.example.llmshadow.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MismatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public MismatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String requestId, String primaryJson, String candidateJson) {
        jdbcTemplate.update("""
                INSERT INTO mismatches (request_id, primary_json, candidate_json, created_at)
                VALUES (?, ?, ?, ?)
                """, requestId, primaryJson, candidateJson, Instant.now().toString());
    }

    public List<MismatchRecord> findRecent(int limit) {
        return jdbcTemplate.query("""
                SELECT id, request_id, primary_json, candidate_json, created_at
                FROM mismatches
                ORDER BY id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new MismatchRecord(
                        rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("primary_json"),
                        rs.getString("candidate_json"),
                        rs.getString("created_at")),
                limit);
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mismatches", Long.class);
        return count == null ? 0 : count;
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM mismatches");
    }
}
