package com.fdmultimedia.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerAgentCapabilitiesTest {

    @Test
    void deterministicV2IsAlwaysAdvertised() {
        assertEquals(
                List.of("DETERMINISTIC_V1", "DETERMINISTIC_V2"),
                WorkerAgent.supportedHighlightAnalyzers(false));
    }

    @Test
    void semanticAnalyzerIsAdvertisedWhenAvailable() {
        assertEquals(
                List.of("DETERMINISTIC_V1", "DETERMINISTIC_V2", "TRANSCRIPT_SEMANTIC_V1"),
                WorkerAgent.supportedHighlightAnalyzers(true));
    }
}
