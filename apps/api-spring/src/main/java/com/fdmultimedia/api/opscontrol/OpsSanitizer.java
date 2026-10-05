package com.fdmultimedia.api.opscontrol;

import java.util.regex.Pattern;

/**
 * Operator-facing text is derived from stored failure messages, which may echo provider responses or file paths. Everything shown by the
 * control plane passes through here: control characters, URLs (they can be presigned), e-mail addresses, file paths, secret-looking
 * key/value pairs and long opaque tokens are removed, and the result is truncated.
 */
public final class OpsSanitizer {
    public static final int MESSAGE_LIMIT = 160;
    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|authorization|cookie|session|signature|credential)s?\\b\\s*[:=]\\s*\\S+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("\\b[A-Za-z]:\\\\\\S*");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![\\w.])/(?:[\\w.-]+/)+[\\w.-]*");
    private static final Pattern OPAQUE = Pattern.compile("\\b[A-Za-z0-9_\\-+/=]{32,}\\b");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    private OpsSanitizer() {}

    public static String message(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.replaceAll("\\p{Cntrl}+", " ");
        text = URL.matcher(text).replaceAll("[url]");
        text = BEARER.matcher(text).replaceAll("[redacted]");
        text = SECRET_PAIR.matcher(text).replaceAll("[redacted]");
        text = EMAIL.matcher(text).replaceAll("[email]");
        text = WINDOWS_PATH.matcher(text).replaceAll("[path]");
        text = UNIX_PATH.matcher(text).replaceAll("[path]");
        text = OPAQUE.matcher(text).replaceAll("[redacted]");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        return text.length() <= MESSAGE_LIMIT ? text : text.substring(0, MESSAGE_LIMIT - 1) + "…";
    }

    /** Failure categories are machine codes; anything else is not exposed. */
    public static String category(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        return CODE.matcher(trimmed).matches() ? trimmed : "UNCLASSIFIED";
    }
}
