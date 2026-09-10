package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImportUrlValidatorTest {

    private final ImportUrlValidator validator = new ImportUrlValidator();

    @Test
    void acceptsPublicHttpUrl() throws Exception {
        assertEquals("https://93.184.216.34/video.mp4", validator.validate("https://93.184.216.34/video.mp4").toString());
    }

    @Test
    void rejectsUnsafeSchemesAndCredentials() {
        assertTerminal("file:///tmp/video.mp4", "INVALID_URL_SCHEME");
        assertTerminal("ftp://93.184.216.34/video.mp4", "INVALID_URL_SCHEME");
        assertTerminal("https://user:pass@93.184.216.34/video.mp4", "URL_CREDENTIALS_REJECTED");
    }

    @Test
    void rejectsLocalAndPrivateTargets() {
        assertTerminal("http://localhost/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://127.0.0.1/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://10.0.0.1/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://192.168.1.10/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://169.254.169.254/latest/meta-data", "SSRF_BLOCKED");
        assertTerminal("http://[::1]/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://[fc00::1]/video.mp4", "SSRF_BLOCKED");
        assertTerminal("http://[fe80::1]/video.mp4", "SSRF_BLOCKED");
    }

    private void assertTerminal(String url, String code) {
        ImportFailureException ex = assertThrows(ImportFailureException.class, () -> validator.validate(url));
        assertEquals(code, ex.code());
        assertEquals(true, ex.terminal());
    }
}
