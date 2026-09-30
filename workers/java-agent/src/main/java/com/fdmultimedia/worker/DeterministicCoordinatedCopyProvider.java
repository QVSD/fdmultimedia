package com.fdmultimedia.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Named {@code DETERMINISTIC_TEST} everywhere it is surfaced — mirrors
 * {@link DeterministicSocialCopyProvider} but must additionally guarantee
 * genuinely distinct hook/caption-opening/hashtag-set content across every
 * output in the same authorization: the backend's own duplicate-hook,
 * duplicate-caption-opening, and identical-hashtag-set checks would
 * otherwise reject this provider's own deterministic output as if it were
 * an AI mistake (Phase 17F items 45/46/48). Still fully stable/deterministic
 * for the same authorization, still zero network calls — this is what makes
 * full E2E acceptance possible without any provider credentials or a
 * running Ollama instance.
 */
final class DeterministicCoordinatedCopyProvider implements CoordinatedCopyProvider {

    @Override
    public CoordinatedCopyResult generate(CoordinatedCopyAuthorization authorization) {
        String seriesTitle = bounded("Coordinated series for run " + authorization.robotRunId(), authorization.maxSeriesTitleLength());
        List<UUID> outputIds = authorization.expectedOutputIds();
        List<CoordinatedCopyItemResult> items = new ArrayList<>();
        for (int i = 0; i < outputIds.size(); i++) {
            UUID outputId = outputIds.get(i);
            int position = i + 1;
            String hook = bounded(
                    "Part " + position + " of the story worth a look (" + shortId(outputId) + ")",
                    authorization.maxHookLength());
            String caption = bounded(
                    "Deterministic coordinated caption #" + position + " for output " + outputId
                            + " (" + authorization.tone().toLowerCase() + ", " + authorization.language().toLowerCase() + ").",
                    authorization.maxCaptionLength());
            List<String> hashtags = List.of("shorts", "part" + position, authorization.tone().toLowerCase())
                    .stream()
                    .limit(authorization.maxHashtags())
                    .map(tag -> bounded(tag, authorization.maxHashtagLength()))
                    .toList();
            String shortTitle = bounded("Clip " + position, authorization.maxShortTitleLength());
            String continuityNote = position == 1 ? null : bounded("Follows part " + (position - 1), authorization.maxContinuityNoteLength());
            items.add(new CoordinatedCopyItemResult(outputId, hook, caption, hashtags, shortTitle, continuityNote));
        }
        return new CoordinatedCopyResult(seriesTitle, List.copyOf(items));
    }

    private String shortId(UUID id) {
        String raw = id.toString();
        return raw.substring(0, Math.min(8, raw.length()));
    }

    private String bounded(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
