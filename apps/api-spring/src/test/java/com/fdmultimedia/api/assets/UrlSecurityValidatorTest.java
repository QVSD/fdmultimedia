package com.fdmultimedia.api.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UrlSecurityValidatorTest {

    private final UrlSecurityValidator validator = new UrlSecurityValidator();

    @Test
    void acceptsPublicHttpAndHttpsUrls() {
        URI uri = validator.validateHttpUrl("https://93.184.216.34/video.mp4");

        assertThat(uri.toString()).isEqualTo("https://93.184.216.34/video.mp4");
    }

    @Test
    void rejectsUnsafeSchemesAndEmbeddedCredentials() {
        assertBadRequest("file:///etc/passwd");
        assertBadRequest("ftp://93.184.216.34/video.mp4");
        assertBadRequest("data:text/plain,hello");
        assertBadRequest("https://user:password@93.184.216.34/video.mp4");
    }

    @Test
    void rejectsLocalhostLoopbackPrivateAndMetadataAddresses() {
        assertBadRequest("https://localhost/video.mp4");
        assertBadRequest("https://127.0.0.1/video.mp4");
        assertBadRequest("https://10.0.0.5/video.mp4");
        assertBadRequest("https://172.16.0.5/video.mp4");
        assertBadRequest("https://192.168.1.20/video.mp4");
        assertBadRequest("https://169.254.169.254/latest/meta-data");
        assertBadRequest("https://[::1]/video.mp4");
        assertBadRequest("https://[fc00::1]/video.mp4");
        assertBadRequest("https://[fe80::1]/video.mp4");
    }

    @Test
    void revalidatesRedirectTargets() {
        assertThatThrownBy(() -> validator.validateRedirectTarget(URI.create("http://127.0.0.1/internal")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void assertBadRequest(String url) {
        assertThatThrownBy(() -> validator.validateHttpUrl(url))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
