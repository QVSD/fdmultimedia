package com.fdmultimedia.api.contentsuggestions;

import java.util.UUID;

/**
 * Bounded, authorized context for one generation request — never entire DB
 * entities. {@code transcriptExcerpt} (when present) is untrusted source
 * data: {@link SocialCopyPromptBuilder} must delimit it clearly and instruct
 * the provider not to treat it as instructions.
 */
public record ContentEnrichmentContext(
        UUID draftId,
        String draftTitle,
        String draftCaption,
        String sourceAssetFilename,
        Long sourceAssetDurationMs,
        String highlightReason,
        String highlightScore,
        Long highlightStartMs,
        Long highlightEndMs,
        boolean transcriptUsed,
        UUID transcriptId,
        String transcriptExcerpt,
        int transcriptSegmentCount) {
}
