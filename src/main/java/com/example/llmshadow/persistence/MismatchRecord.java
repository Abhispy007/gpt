package com.example.llmshadow.persistence;

public record MismatchRecord(
        long id,
        String requestId,
        String primaryJson,
        String candidateJson,
        String createdAt) {
}
