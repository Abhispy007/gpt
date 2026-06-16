package com.example.llmshadow.controller;

import com.example.llmshadow.dto.LlmProxyRequest;
import com.example.llmshadow.dto.ShadowComparisonJob;
import com.example.llmshadow.service.BackpressureService;
import com.example.llmshadow.service.PrimaryLlmClient;
import com.example.llmshadow.service.RequestValidationService;
import com.example.llmshadow.service.ShadowComparisonService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@Tag(name = "Proxy", description = "Customer-facing endpoint that returns Primary output and shadows Candidate output.")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final PrimaryLlmClient primaryLlmClient;
    private final ShadowComparisonService shadowComparisonService;
    private final RequestValidationService requestValidationService;
    private final BackpressureService backpressureService;

    public ProxyController(
            PrimaryLlmClient primaryLlmClient,
            ShadowComparisonService shadowComparisonService,
            RequestValidationService requestValidationService,
            BackpressureService backpressureService) {
        this.primaryLlmClient = primaryLlmClient;
        this.shadowComparisonService = shadowComparisonService;
        this.requestValidationService = requestValidationService;
        this.backpressureService = backpressureService;
    }

    @PostMapping(value = "/proxy", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Proxy a request to the Primary LLM and shadow the Candidate LLM",
            description = "Returns the Primary response synchronously while submitting an asynchronous Candidate comparison job.")
    @ApiResponse(responseCode = "200", description = "Primary LLM response returned successfully")
    @ApiResponse(responseCode = "400", description = "Request failed validation or requested unsupported streaming")
    @ApiResponse(responseCode = "413", description = "Request exceeds configured size or token limits")
    @ApiResponse(responseCode = "429", description = "Ingress rate limit exceeded")
    @ApiResponse(responseCode = "503", description = "Ingress backpressure threshold exceeded")
    @ApiResponse(responseCode = "502", description = "Primary LLM mock failed")
    @RateLimiter(name = "proxy")
    public ResponseEntity<String> proxy(@Valid @RequestBody LlmProxyRequest request) {
        requestValidationService.validate(request);
        backpressureService.assertAcceptingTraffic();

        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        String primaryRawResponse;

        try {
            primaryRawResponse = primaryLlmClient.complete(request);
        } catch (RuntimeException ex) {
            MDC.remove("requestId");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Primary LLM mock failed", ex);
        }

        ShadowComparisonJob job = new ShadowComparisonJob(requestId, request, primaryRawResponse, Instant.now());
        try {
            shadowComparisonService.submit(job);
        } catch (RuntimeException ex) {
            log.warn("event=shadow_submit_failed requestId={} message={}", requestId, ex.getMessage());
        } finally {
            MDC.remove("requestId");
        }

        return ResponseEntity
                .ok()
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(primaryRawResponse);
    }
}
