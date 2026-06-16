package com.example.llmshadow.service;

import com.example.llmshadow.persistence.MismatchRepository;
import com.example.llmshadow.persistence.ShadowMetricsRepository;
import com.example.llmshadow.persistence.ShadowMetricsSnapshot;
import com.example.llmshadow.service.JsonComparisonService.JsonComparisonResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShadowMetricsService {

    private final MismatchRepository mismatchRepository;
    private final RedactionService redactionService;
    private final ShadowMetricsRepository shadowMetricsRepository;

    public ShadowMetricsService(
            MismatchRepository mismatchRepository,
            RedactionService redactionService,
            ShadowMetricsRepository shadowMetricsRepository) {
        this.mismatchRepository = mismatchRepository;
        this.redactionService = redactionService;
        this.shadowMetricsRepository = shadowMetricsRepository;
    }

    @Transactional
    public void recordCompletedComparison(String requestId, JsonComparisonResult result) {
        if (!result.matched()) {
            mismatchRepository.save(
                    requestId,
                    redactionService.redactToString(result.primaryJson()),
                    redactionService.redactToString(result.candidateJson()));
        }

        shadowMetricsRepository.recordCompletedComparison(result.matched());
    }

    public ShadowMetricsSnapshot current() {
        return shadowMetricsRepository.current();
    }
}
