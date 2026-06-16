package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.AppProperties;
import com.example.llmshadow.dto.LlmProxyRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class RequestValidationService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public RequestValidationService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public void validate(LlmProxyRequest request) {
        AppProperties.RequestLimits limits = appProperties.requestLimits();

        if (request.stream()) {
            throw new RequestRejectedException(
                    "Streaming responses are not supported by this deployment",
                    RequestRejectedException.Reason.STREAMING_NOT_SUPPORTED);
        }

        if (request.prompt().length() > limits.maxPromptChars()) {
            throw new RequestRejectedException(
                    "Prompt exceeds configured character limit",
                    RequestRejectedException.Reason.REQUEST_TOO_LARGE);
        }

        JsonNode inputJson = objectMapper.valueToTree(request.input());
        int inputDepth = depth(inputJson);
        if (inputDepth > limits.maxInputDepth()) {
            throw new RequestRejectedException(
                    "Input JSON exceeds configured depth limit",
                    RequestRejectedException.Reason.REQUEST_TOO_LARGE);
        }

        int inputBytes = serializedSize(inputJson);
        if (inputBytes > limits.maxInputJsonBytes()) {
            throw new RequestRejectedException(
                    "Input JSON exceeds configured byte limit",
                    RequestRejectedException.Reason.REQUEST_TOO_LARGE);
        }

        long estimatedTokens = estimateTokens(request.prompt(), inputBytes);
        if (estimatedTokens > limits.maxEstimatedTokens()) {
            throw new RequestRejectedException(
                    "Request exceeds configured estimated token limit",
                    RequestRejectedException.Reason.REQUEST_TOO_LARGE);
        }
    }

    private int serializedSize(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException ex) {
            throw new RequestRejectedException(
                    "Input JSON could not be measured",
                    RequestRejectedException.Reason.INVALID_REQUEST);
        }
    }

    private long estimateTokens(String prompt, int inputBytes) {
        long promptTokens = Math.ceilDiv(prompt.length(), 4);
        long inputTokens = Math.ceilDiv(inputBytes, 4);
        return promptTokens + inputTokens;
    }

    private int depth(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return 1;
        }

        int maxChildDepth = 0;
        for (JsonNode child : node) {
            maxChildDepth = Math.max(maxChildDepth, depth(child));
        }
        return maxChildDepth + 1;
    }
}
