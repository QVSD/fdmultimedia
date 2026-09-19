package com.fdmultimedia.worker;

import java.util.List;

/**
 * Named {@code DETERMINISTIC_TEST} everywhere it is surfaced — it never
 * pretends to be real AI. Given the same authorization it always produces
 * the same structured output, entirely from the bounded fields already
 * shipped by the backend (no network call, no external dependency), which
 * is what makes full E2E acceptance possible without any provider
 * credentials or a running Ollama instance.
 */
final class DeterministicSocialCopyProvider implements ContentEnrichmentProvider {

    @Override
    public SocialCopyResult generate(SocialCopyAuthorization authorization) {
        long startNanos = System.nanoTime();
        String sourceFile = extractLineValue(authorization.prompt(), "Source file:");
        String hook = bounded("New clip worth a look" + (sourceFile != null ? ": " + sourceFile : "."), authorization.maxHookLength());
        String caption = bounded(
                "Deterministic test caption for draft " + authorization.contentDraftId()
                        + " (" + authorization.tone().toLowerCase() + ", " + authorization.language().toLowerCase() + ").",
                authorization.maxCaptionLength());
        List<String> hashtags = List.of("shorts", "contentcreator", authorization.tone().toLowerCase())
                .stream()
                .limit(authorization.maxHashtags())
                .map(tag -> bounded(tag, authorization.maxHashtagLength()))
                .toList();
        String shortTitle = bounded("Quick clip", authorization.maxShortTitleLength());
        long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        return new SocialCopyResult(hook, caption, hashtags, shortTitle, null, null, null, latencyMs);
    }

    private String extractLineValue(String prompt, String label) {
        for (String line : prompt.split("\n")) {
            if (line.startsWith(label)) {
                return line.substring(label.length()).trim();
            }
        }
        return null;
    }

    private String bounded(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
