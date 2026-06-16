package com.example.llmshadow.persistence;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS shadow_jobs");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mismatches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT NOT NULL,
                    primary_json TEXT NOT NULL,
                    candidate_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS shadow_metrics (
                    id INTEGER PRIMARY KEY,
                    total_comparisons INTEGER NOT NULL DEFAULT 0,
                    mismatch_count INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS shadow_outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
    }
}
