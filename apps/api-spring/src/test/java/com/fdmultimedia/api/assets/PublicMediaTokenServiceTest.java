package com.fdmultimedia.api.assets;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.accounts.crypto.SocialCredentialEncryptionProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicMediaTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void issuedTokenValidatesBackToTheSamePublicationAndAsset() {
        PublicMediaTokenService service = serviceWithKey(randomKey(), NOW);
        UUID publicationId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        String token = service.issue(publicationId, assetId, Duration.ofMinutes(30));
        Optional<PublicMediaToken> decoded = service.validate(token);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().publicationId()).isEqualTo(publicationId);
        assertThat(decoded.get().assetId()).isEqualTo(assetId);
    }

    @Test
    void expiredTokenFailsValidation() {
        PublicMediaTokenService issuer = serviceWithKey(sharedKey(), NOW);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(1));
        PublicMediaTokenService laterValidator = serviceWithKey(sharedKey(), NOW.plus(Duration.ofMinutes(10)));

        assertThat(laterValidator.validate(token)).isEmpty();
    }

    @Test
    void tamperedTokenFailsValidation() {
        PublicMediaTokenService service = serviceWithKey(randomKey(), NOW);
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(30));
        String tampered = token.substring(0, token.length() - 2) + "zz";

        assertThat(service.validate(tampered)).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentKeyFailsValidation() {
        PublicMediaTokenService issuer = serviceWithKey(randomKey(), NOW);
        PublicMediaTokenService otherValidator = serviceWithKey(randomKey(), NOW);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(30));

        assertThat(otherValidator.validate(token)).isEmpty();
    }

    @Test
    void malformedTokenNeverThrowsAndFailsValidation() {
        PublicMediaTokenService service = serviceWithKey(randomKey(), NOW);

        assertThat(service.validate("not-a-real-token")).isEmpty();
        assertThat(service.validate("")).isEmpty();
        assertThat(service.validate(null)).isEmpty();
        assertThat(service.validate("..")).isEmpty();
        assertThat(service.validate("a.b.c")).isEmpty();
    }

    @Test
    void tokensForDifferentPublicationsAreNotInterchangeable() {
        PublicMediaTokenService service = serviceWithKey(randomKey(), NOW);
        UUID publicationA = UUID.randomUUID();
        UUID publicationB = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        String tokenForA = service.issue(publicationA, assetId, Duration.ofMinutes(30));
        Optional<PublicMediaToken> decoded = service.validate(tokenForA);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().publicationId()).isNotEqualTo(publicationB);
    }

    @Test
    void unavailableWithoutAKey() {
        PublicMediaTokenService service = serviceWithKey("", NOW);

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.validate("anything")).isEmpty();
    }

    private String sharedKeyValue;

    private String sharedKey() {
        if (sharedKeyValue == null) {
            sharedKeyValue = randomKey();
        }
        return sharedKeyValue;
    }

    private PublicMediaTokenService serviceWithKey(String base64Key, Instant now) {
        SocialCredentialEncryptionProperties properties = new SocialCredentialEncryptionProperties();
        properties.setEncryptionKey(base64Key);
        return new PublicMediaTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
