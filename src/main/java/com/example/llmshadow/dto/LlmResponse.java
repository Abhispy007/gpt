package com.example.llmshadow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Mock LLM response returned by Primary and Candidate endpoints.")
public record LlmResponse(
        @Schema(example = "primary")
        String model,

        @Schema(description = "Parsed model output.")
        JsonNode output) {
}
