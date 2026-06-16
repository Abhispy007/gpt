package com.example.llmshadow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;

@Schema(description = "Request payload sent to the Primary model and shadowed to the Candidate model.")
public record LlmProxyRequest(
        @Schema(example = "Return customer tier")
        @NotBlank
        @Size(max = 8000)
        String prompt,

        @Schema(description = "Arbitrary request input. Use customerId in examples for deterministic mock output.")
        @NotNull
        Map<String, Object> input,

        @Schema(description = "When true, the Candidate mock returns a different tier.", example = "false")
        boolean forceMismatch,

        @Schema(description = "When true, the Candidate mock returns HTTP 500.", example = "false")
        boolean forceCandidateError,

        @Schema(description = "Candidate mock delay in milliseconds.", example = "0")
        @PositiveOrZero
        long candidateDelayMs,

        @Schema(description = "Streaming is rejected unless explicitly enabled; this demo buffers whole JSON responses.")
        boolean stream) {
}
