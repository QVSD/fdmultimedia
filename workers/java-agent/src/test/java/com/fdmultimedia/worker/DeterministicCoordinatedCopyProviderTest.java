package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicCoordinatedCopyProviderTest {

    private final DeterministicCoordinatedCopyProvider provider = new DeterministicCoordinatedCopyProvider();

    @Test
    void producesStableOutputForTheSameAuthorization() throws Exception {
        CoordinatedCopyAuthorization authorization = authorization(3);

        CoordinatedCopyResult first = provider.generate(authorization);
        CoordinatedCopyResult second = provider.generate(authorization);

        assertEquals(first, second);
    }

    @Test
    void producesExactlyOneItemPerExpectedOutputInOrder() throws Exception {
        CoordinatedCopyAuthorization authorization = authorization(3);

        CoordinatedCopyResult result = provider.generate(authorization);

        assertEquals(3, result.items().size());
        for (int i = 0; i < authorization.expectedOutputIds().size(); i++) {
            assertEquals(authorization.expectedOutputIds().get(i), result.items().get(i).outputId());
        }
    }

    @Test
    void neverProducesDuplicateHooksAcrossOutputs() throws Exception {
        CoordinatedCopyResult result = provider.generate(authorization(5));

        Set<String> hooks = new HashSet<>();
        result.items().forEach(item -> hooks.add(item.hook()));
        assertEquals(result.items().size(), hooks.size());
    }

    @Test
    void neverProducesIdenticalHashtagSetsAcrossOutputs() throws Exception {
        CoordinatedCopyResult result = provider.generate(authorization(5));

        Set<Set<String>> distinctSets = new HashSet<>();
        result.items().forEach(item -> distinctSets.add(new HashSet<>(item.hashtags())));
        assertTrue(distinctSets.size() > 1);
    }

    @Test
    void neverProducesRepeatedCaptionOpenings() throws Exception {
        CoordinatedCopyResult result = provider.generate(authorization(5));

        Set<String> openings = new HashSet<>();
        result.items().forEach(item -> openings.add(item.caption().substring(0, Math.min(60, item.caption().length()))));
        assertEquals(result.items().size(), openings.size());
    }

    @Test
    void firstItemHasNoContinuityNoteAndLaterItemsDo() throws Exception {
        CoordinatedCopyResult result = provider.generate(authorization(3));

        assertNull(result.items().get(0).continuityNote());
        assertTrue(result.items().get(1).continuityNote() != null);
        assertTrue(result.items().get(2).continuityNote() != null);
    }

    @Test
    void neverExceedsBoundsFromAuthorization() throws Exception {
        CoordinatedCopyAuthorization authorization = new CoordinatedCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "DETERMINISTIC_TEST", "deterministic-v1", "SOCIAL_COPY_V4_COORDINATED",
                "prompt", List.of(UUID.randomUUID(), UUID.randomUUID()), "ENGLISH", "ENERGETIC",
                10, 12, 20, 2, 5, 8, 15);

        CoordinatedCopyResult result = provider.generate(authorization);

        assertTrue(result.seriesTitle().length() <= 10);
        result.items().forEach(item -> {
            assertTrue(item.hook().length() <= 12);
            assertTrue(item.caption().length() <= 20);
            assertTrue(item.hashtags().size() <= 2);
            item.hashtags().forEach(tag -> assertTrue(tag.length() <= 5));
            assertTrue(item.shortTitle().length() <= 8);
        });
    }

    private CoordinatedCopyAuthorization authorization(int outputCount) {
        List<UUID> outputIds = new java.util.ArrayList<>();
        for (int i = 0; i < outputCount; i++) {
            outputIds.add(UUID.randomUUID());
        }
        return new CoordinatedCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "DETERMINISTIC_TEST", "deterministic-v1", "SOCIAL_COPY_V4_COORDINATED",
                "prompt", List.copyOf(outputIds), "ENGLISH", "CASUAL", 120, 200, 2200, 30, 50, 100, 200);
    }
}
