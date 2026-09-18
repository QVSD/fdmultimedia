package com.fdmultimedia.api.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Deliberately unauthenticated: a real publishing provider (Meta) must be
 * able to fetch media bytes over the public internet, and it cannot present
 * our session cookie or CSRF token. Every other safety property is enforced
 * instead by the token itself and {@link PublicMediaAccessService} — see
 * {@link PublicMediaTokenService} for the token format and
 * docs/ARCHITECTURE.md for the full trust-boundary writeup.
 *
 * <p>This endpoint never accepts a raw asset id, storage key, or bucket name
 * from the caller: the token cryptographically names one Publication, and the
 * storage key actually read is always resolved server-side from that
 * Publication's own asset row. There is no path here that can read an
 * arbitrary object, list a directory, or expose storage credentials.
 */
@RestController
@RequestMapping("/api/public-media")
public class PublicMediaController {

    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    private final PublicMediaTokenService tokenService;
    private final PublicMediaAccessService accessService;
    private final ObjectStorageService storage;
    private final MediaProperties mediaProperties;

    public PublicMediaController(
            PublicMediaTokenService tokenService,
            PublicMediaAccessService accessService,
            ObjectStorageService storage,
            MediaProperties mediaProperties) {
        this.tokenService = tokenService;
        this.accessService = accessService;
        this.storage = storage;
        this.mediaProperties = mediaProperties;
    }

    @GetMapping("/{token}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String token) {
        Optional<PublicMediaToken> decoded = tokenService.validate(token);
        if (decoded.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<PublicMediaObject> object = accessService.resolve(decoded.get());
        if (object.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PublicMediaObject media = object.get();
        long maxBytes = mediaProperties.getMaxDownloadSizeBytes();
        StreamingResponseBody body = outputStream -> streamBounded(media.storageKey(), outputStream, maxBytes);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(media.contentType() != null
                ? MediaType.parseMediaType(media.contentType())
                : MediaType.APPLICATION_OCTET_STREAM);
        if (media.fileSizeBytes() != null) {
            headers.setContentLength(media.fileSizeBytes());
        }
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private void streamBounded(String storageKey, OutputStream outputStream, long maxBytes) throws IOException {
        try (ResponseInputStream<GetObjectResponse> objectStream = storage.getObjectStream(storageKey)) {
            copyBounded(objectStream, outputStream, maxBytes);
        }
    }

    private void copyBounded(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Object exceeds the maximum allowed streaming size");
            }
            output.write(buffer, 0, read);
        }
    }
}
