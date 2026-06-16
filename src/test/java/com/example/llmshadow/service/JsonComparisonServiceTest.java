package com.example.llmshadow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.llmshadow.service.JsonComparisonService.JsonComparisonResult;
import com.example.llmshadow.service.JsonComparisonService.JsonExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonComparisonServiceTest {

    private JsonComparisonService service;

    @BeforeEach
    void setUp() {
        service = new JsonComparisonService(new ObjectMapper());
    }

    @Test
    void extractsRawJson() {
        JsonExtractionResult result = service.extractJson("{\"output\":{\"tier\":\"gold\"}}");

        assertThat(result.success()).isTrue();
        assertThat(result.json().get("output").get("tier").asText()).isEqualTo("gold");
    }

    @Test
    void extractsFencedJson() {
        JsonExtractionResult result = service.extractJson("""
                Here is the answer:
                ```json
                {"output":{"tier":"gold"}}
                ```
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.json().get("output").get("tier").asText()).isEqualTo("gold");
    }

    @Test
    void extractsProseWrappedJson() {
        JsonExtractionResult result = service.extractJson("Candidate said: {\"output\":{\"tier\":\"gold\"}}. Done.");

        assertThat(result.success()).isTrue();
        assertThat(result.json().get("output").get("tier").asText()).isEqualTo("gold");
    }

    @Test
    void malformedJsonFailsCleanly() {
        JsonExtractionResult result = service.extractJson("there is no json here");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no valid JSON");
    }

    @Test
    void objectFieldOrderDoesNotCauseMismatch() {
        JsonComparisonResult result = service.compareOutputs(
                "{\"model\":\"primary\",\"output\":{\"a\":1,\"b\":2}}",
                "{\"model\":\"candidate\",\"output\":{\"b\":2,\"a\":1}}");

        assertThat(result.comparable()).isTrue();
        assertThat(result.matched()).isTrue();
        assertThat(result.primaryHash()).isEqualTo(result.candidateHash());
    }

    @Test
    void differentValuesCauseMismatch() {
        JsonComparisonResult result = service.compareOutputs(
                "{\"model\":\"primary\",\"output\":{\"tier\":\"gold\"}}",
                "{\"model\":\"candidate\",\"output\":{\"tier\":\"silver\"}}");

        assertThat(result.comparable()).isTrue();
        assertThat(result.matched()).isFalse();
        assertThat(result.primaryHash()).isNotEqualTo(result.candidateHash());
    }

    @Test
    void trimsTextValuesBeforeHashComparison() {
        JsonComparisonResult result = service.compareOutputs(
                "{\"output\":{\"answer\":\" gold \",\"metadata\":{\"reason\":\" approved \"}}}",
                "{\"output\":{\"metadata\":{\"reason\":\"approved\"},\"answer\":\"gold\"}}");

        assertThat(result.comparable()).isTrue();
        assertThat(result.matched()).isTrue();
        assertThat(result.primaryHash()).isEqualTo(result.candidateHash());
        assertThat(result.primaryJson().get("answer").asText()).isEqualTo("gold");
    }

    @Test
    void hashesCanonicalPayloadInsteadOfWrapperMetadata() {
        JsonComparisonResult result = service.compareOutputs(
                "{\"model\":\"primary\",\"output\":{\"tier\":\"gold\"}}",
                "{\"model\":\"candidate\",\"output\":{\"tier\":\"gold\"}}");

        assertThat(result.comparable()).isTrue();
        assertThat(result.matched()).isTrue();
        assertThat(result.primaryHash()).isEqualTo(result.candidateHash());
        assertThat(result.primaryHash()).hasSize(64);
    }
}
