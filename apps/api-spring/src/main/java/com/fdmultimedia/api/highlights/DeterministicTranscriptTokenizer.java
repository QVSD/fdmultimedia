package com.fdmultimedia.api.highlights;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeterministicTranscriptTokenizer {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:'[\\p{L}]+)?");

    private DeterministicTranscriptTokenizer() {}

    static Set<String> tokenSet(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return Set.copyOf(result);
    }
}
