package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpsSanitizerTest {
    @Test
    void presignedUrlsAndTokensAreRemoved() {
        String message = OpsSanitizer.message("Upload failed https://minio.local:9000/media/a?X-Amz-Signature=abcdef0123456789&X-Amz-Credential=AKIA token=abc123 done");
        assertThat(message).doesNotContain("https").doesNotContain("Signature").doesNotContain("abc123").doesNotContain("AKIA");
        assertThat(message).contains("[url]").contains("done");
    }

    @Test
    void secretPairsBearerEmailAndPathsAreRedacted() {
        String message = OpsSanitizer.message("password=hunter2 Authorization: Bearer eyJhbGciOi.payload.sig user bob@example.com at C:\\Users\\bob\\x.mp4 and /var/data/secret/file.bin");
        assertThat(message).doesNotContain("hunter2").doesNotContain("eyJhbGciOi").doesNotContain("bob@example.com")
                .doesNotContain("C:\\Users").doesNotContain("/var/data");
    }

    @Test
    void longOpaqueTokensAreRedactedAndMessagesAreTruncated() {
        assertThat(OpsSanitizer.message("key 0123456789abcdef0123456789abcdef0123456789")).doesNotContain("0123456789abcdef");
        String longMessage = OpsSanitizer.message("word ".repeat(100));
        assertThat(longMessage.length()).isLessThanOrEqualTo(OpsSanitizer.MESSAGE_LIMIT);
        assertThat(longMessage).endsWith("…");
    }

    @Test
    void controlCharactersAreCollapsedAndBlankIsNull() {
        assertThat(OpsSanitizer.message("a\nb\tc\u0000d")).isEqualTo("a b c d");
        assertThat(OpsSanitizer.message("   ")).isNull();
        assertThat(OpsSanitizer.message(null)).isNull();
    }

    @Test
    void categoriesAreMachineCodesOnly() {
        assertThat(OpsSanitizer.category("PROVIDER_REJECTED")).isEqualTo("PROVIDER_REJECTED");
        assertThat(OpsSanitizer.category("has spaces and secret=1")).isEqualTo("UNCLASSIFIED");
        assertThat(OpsSanitizer.category("")).isNull();
        assertThat(OpsSanitizer.category(null)).isNull();
    }
}
