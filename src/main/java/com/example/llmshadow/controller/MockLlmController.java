package com.example.llmshadow.controller;

import com.example.llmshadow.dto.LlmProxyRequest;
import com.example.llmshadow.dto.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock")
@Tag(name = "Mock LLMs", description = "Internal mock endpoints used by the proxy and shadow comparison flow.")
public class MockLlmController {

    private final ObjectMapper objectMapper;

    public MockLlmController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostMapping("/primary")
    @Operation(summary = "Simulate the Primary LLM")
    public LlmResponse primary(@Valid @RequestBody LlmProxyRequest request) {
        return new LlmResponse("primary", outputFor(request, "gold"));
    }

    @PostMapping("/candidate")
    @Operation(summary = "Simulate the Candidate LLM")
    public ResponseEntity<?> candidate(@Valid @RequestBody LlmProxyRequest request) {
        delayIfRequested(request.candidateDelayMs());

        if (request.forceCandidateError()) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "candidate model failed"));
        }

        String tier = request.forceMismatch() ? "silver" : "gold";
        return ResponseEntity.ok(new LlmResponse("candidate", outputFor(request, tier)));
    }

    private JsonNode outputFor(LlmProxyRequest request, String tier) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("customerId", customerIdFrom(request));
        output.put("tier", tier);
        output.put("answer", "Processed prompt: " + request.prompt());
        return output;
    }

    private String customerIdFrom(LlmProxyRequest request) {
        Object value = request.input().get("customerId");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private void delayIfRequested(long delayMs) {
        if (delayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
