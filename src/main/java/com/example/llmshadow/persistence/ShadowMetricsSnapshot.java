package com.example.llmshadow.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ShadowMetricsSnapshot(
        long totalComparisons,
        long matches,
        long mismatches,
        double matchRatePercentage,
        String updatedAt) {

    public static ShadowMetricsSnapshot of(long totalComparisons, long mismatches, String updatedAt) {
        long sanitizedMismatches = Math.max(0, Math.min(mismatches, totalComparisons));
        long matches = totalComparisons - sanitizedMismatches;
        double matchRatePercentage = totalComparisons == 0
                ? 0.0
                : BigDecimal.valueOf(matches * 100.0 / totalComparisons)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();

        return new ShadowMetricsSnapshot(
                totalComparisons,
                matches,
                sanitizedMismatches,
                matchRatePercentage,
                updatedAt);
    }

    public static ShadowMetricsSnapshot empty() {
        return new ShadowMetricsSnapshot(0, 0, 0, 0.0, null);
    }
}
