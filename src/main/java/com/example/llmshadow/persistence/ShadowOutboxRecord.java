package com.example.llmshadow.persistence;

import com.example.llmshadow.dto.ShadowComparisonJob;

public record ShadowOutboxRecord(
        long id,
        ShadowComparisonJob job) {
}
