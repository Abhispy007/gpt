package com.example.llmshadow.controller;

import com.example.llmshadow.persistence.ShadowMetricsSnapshot;
import com.example.llmshadow.service.ShadowMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metrics")
@Tag(name = "Metrics", description = "Real-time shadow comparison match metrics.")
public class MetricsController {

    private final ShadowMetricsService shadowMetricsService;

    public MetricsController(ShadowMetricsService shadowMetricsService) {
        this.shadowMetricsService = shadowMetricsService;
    }

    @GetMapping
    @Operation(summary = "Get current shadow comparison match rate")
    public ShadowMetricsSnapshot metrics() {
        return shadowMetricsService.current();
    }
}
