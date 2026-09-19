package com.fdmultimedia.worker;

import java.util.List;

/** Still-untrusted, Worker-parsed provider output; the backend does the authoritative validation. */
record SocialCopyResult(
        String hook,
        String caption,
        List<String> hashtags,
        String shortTitle,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs) {
}
