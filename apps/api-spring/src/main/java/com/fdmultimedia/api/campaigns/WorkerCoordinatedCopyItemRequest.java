package com.fdmultimedia.api.campaigns;

import java.util.List;
import java.util.UUID;

/** Raw, still-untrusted per-output copy item as parsed by the Worker — the backend performs all authoritative validation. */
public record WorkerCoordinatedCopyItemRequest(
        UUID outputId,
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        String continuityNote) {
}
