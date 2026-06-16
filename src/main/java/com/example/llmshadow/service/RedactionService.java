package com.example.llmshadow.service;

import com.example.llmshadow.config.properties.ShadowProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RedactionService {

    private final ObjectMapper objectMapper;
    private final Set<String> sensitiveKeys;

    public RedactionService(
            ObjectMapper objectMapper,
            ShadowProperties shadowProperties) {
        this.objectMapper = objectMapper;
        this.sensitiveKeys = shadowProperties.redaction().sensitiveKeys().stream()
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }

        JsonNode copy = node.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    public String redactToString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(redact(node));
        } catch (JsonProcessingException ex) {
            return String.valueOf(node);
        }
    }

    private void redactInPlace(JsonNode node) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (sensitiveKeys.contains(fieldName.toLowerCase(Locale.ROOT))) {
                    objectNode.put(fieldName, "[REDACTED]");
                } else {
                    redactInPlace(objectNode.get(fieldName));
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            arrayNode.forEach(this::redactInPlace);
        }
    }
}
