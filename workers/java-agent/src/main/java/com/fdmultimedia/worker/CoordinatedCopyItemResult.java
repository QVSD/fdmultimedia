package com.fdmultimedia.worker;

import java.util.List;
import java.util.UUID;

/** Still-untrusted, Worker-parsed provider output for a single output; the backend does the authoritative validation. */
record CoordinatedCopyItemResult(
        UUID outputId,
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        String continuityNote) {
}
