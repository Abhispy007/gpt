package com.example.llmshadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class JsonComparisonService {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public JsonComparisonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonComparisonResult compareOutputs(String primaryRaw, String candidateRaw) {
        JsonExtractionResult primary = extractJson(primaryRaw);
        if (!primary.success()) {
            return JsonComparisonResult.extractionFailure("primary", primary.error());
        }

        JsonExtractionResult candidate = extractJson(candidateRaw);
        if (!candidate.success()) {
            return JsonComparisonResult.extractionFailure("candidate", candidate.error());
        }

        JsonNode primaryOutput = comparablePayload(primary.json());
        JsonNode candidateOutput = comparablePayload(candidate.json());
        JsonNode normalizedPrimary = normalize(primaryOutput);
        JsonNode normalizedCandidate = normalize(candidateOutput);
        String primaryCanonical = canonicalJson(normalizedPrimary);
        String candidateCanonical = canonicalJson(normalizedCandidate);
        String primaryHash = sha256(primaryCanonical);
        String candidateHash = sha256(candidateCanonical);
        return JsonComparisonResult.compared(
                primaryHash.equals(candidateHash),
                normalizedPrimary,
                normalizedCandidate,
                primaryHash,
                candidateHash);
    }

    public JsonExtractionResult extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return JsonExtractionResult.failure("empty response");
        }

        Optional<JsonNode> wholeDocument = parse(raw.trim());
        if (wholeDocument.isPresent()) {
            return JsonExtractionResult.success(wholeDocument.get());
        }

        Matcher matcher = FENCED_JSON.matcher(raw);
        while (matcher.find()) {
            Optional<JsonNode> fenced = parse(matcher.group(1).trim());
            if (fenced.isPresent()) {
                return JsonExtractionResult.success(fenced.get());
            }
        }

        Optional<JsonNode> balanced = findFirstBalancedJson(raw).flatMap(this::parse);
        return balanced
                .map(JsonExtractionResult::success)
                .orElseGet(() -> JsonExtractionResult.failure("no valid JSON object or array found"));
    }

    private JsonNode comparablePayload(JsonNode jsonNode) {
        if (jsonNode != null && jsonNode.isObject() && jsonNode.has("output")) {
            return jsonNode.get("output");
        }
        return jsonNode;
    }

    private JsonNode normalize(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return objectMapper.nullNode();
        }

        if (jsonNode.isObject()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> sortedFields = new TreeMap<>();
            jsonNode.fields().forEachRemaining(entry -> sortedFields.put(entry.getKey(), entry.getValue()));
            sortedFields.forEach((fieldName, fieldValue) -> normalized.set(fieldName, normalize(fieldValue)));
            return normalized;
        }

        if (jsonNode.isArray()) {
            ArrayNode normalized = objectMapper.createArrayNode();
            jsonNode.forEach(element -> normalized.add(normalize(element)));
            return normalized;
        }

        if (jsonNode.isTextual()) {
            return objectMapper.getNodeFactory().textNode(jsonNode.textValue().trim());
        }

        return jsonNode;
    }

    private String canonicalJson(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not canonicalize JSON", ex);
        }
    }

    private String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Optional<JsonNode> parse(String raw) {
        try {
            return Optional.of(objectMapper.readTree(raw));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> findFirstBalancedJson(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char start = raw.charAt(i);
            if (start == '{' || start == '[') {
                Optional<String> candidate = balancedCandidateFrom(raw, i, start);
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> balancedCandidateFrom(String raw, int startIndex, char opening) {
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = startIndex; i < raw.length(); i++) {
            char current = raw.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (current == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (current == opening) {
                depth++;
            } else if (current == closing) {
                depth--;
                if (depth == 0) {
                    return Optional.of(raw.substring(startIndex, i + 1));
                }
            }
        }

        return Optional.empty();
    }

    public record JsonExtractionResult(boolean success, JsonNode json, String error) {

        public static JsonExtractionResult success(JsonNode json) {
            return new JsonExtractionResult(true, json, null);
        }

        public static JsonExtractionResult failure(String error) {
            return new JsonExtractionResult(false, null, error);
        }
    }

    public record JsonComparisonResult(
            boolean comparable,
            boolean matched,
            JsonNode primaryJson,
            JsonNode candidateJson,
            String primaryHash,
            String candidateHash,
            String failedSource,
            String error) {

        public static JsonComparisonResult compared(
                boolean matched,
                JsonNode primaryJson,
                JsonNode candidateJson,
                String primaryHash,
                String candidateHash) {
            return new JsonComparisonResult(true, matched, primaryJson, candidateJson, primaryHash, candidateHash, null, null);
        }

        public static JsonComparisonResult extractionFailure(String failedSource, String error) {
            return new JsonComparisonResult(false, false, null, null, null, null, failedSource, error);
        }
    }
}
