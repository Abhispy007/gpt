package com.example.llmshadow.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShadowMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShadowMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordCompletedComparison(boolean matched) {
        int mismatchDelta = matched ? 0 : 1;
        jdbcTemplate.update("""
                INSERT INTO shadow_metrics (id, total_comparisons, mismatch_count, updated_at)
                VALUES (1, 1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    total_comparisons = total_comparisons + 1,
                    mismatch_count = mismatch_count + excluded.mismatch_count,
                    updated_at = excluded.updated_at
                """, mismatchDelta, Instant.now().toString());
    }

    public ShadowMetricsSnapshot current() {
        return jdbcTemplate.query("""
                SELECT total_comparisons, mismatch_count, updated_at
                FROM shadow_metrics
                WHERE id = 1
                """, rs -> {
            if (!rs.next()) {
                return ShadowMetricsSnapshot.empty();
            }

            return ShadowMetricsSnapshot.of(
                    rs.getLong("total_comparisons"),
                    rs.getLong("mismatch_count"),
                    rs.getString("updated_at"));
        });
    }

    public void reset() {
        jdbcTemplate.update("DELETE FROM shadow_metrics");
    }
}
