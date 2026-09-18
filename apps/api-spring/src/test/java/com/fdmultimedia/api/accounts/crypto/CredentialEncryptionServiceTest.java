package com.fdmultimedia.api.accounts.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialEncryptionServiceTest {

    @Test
    void isUnavailableWhenKeyIsBlank() {
        CredentialEncryptionService service = serviceWithKey("");

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.encrypt("secret")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedBase64KeyAtConstruction() {
        SocialCredentialEncryptionProperties properties = new SocialCredentialEncryptionProperties();
        properties.setEncryptionKey("not-valid-base64!!!");

        assertThatThrownBy(() -> new CredentialEncryptionService(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongLengthKeyAtConstruction() {
        SocialCredentialEncryptionProperties properties = new SocialCredentialEncryptionProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new CredentialEncryptionService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void encryptsAndDecryptsRoundTrip() {
        CredentialEncryptionService service = serviceWithKey(randomKey());

        String ciphertext = service.encrypt("super-secret-access-token");

        assertThat(ciphertext).startsWith("v1:");
        assertThat(service.decrypt(ciphertext)).isEqualTo("super-secret-access-token");
    }

    @Test
    void sameTokenEncryptsDifferentlyEachTime() {
        CredentialEncryptionService service = serviceWithKey(randomKey());

        String first = service.encrypt("same-token");
        String second = service.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("same-token");
        assertThat(service.decrypt(second)).isEqualTo("same-token");
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        CredentialEncryptionService service = serviceWithKey(randomKey());
        String ciphertext = service.encrypt("super-secret-access-token");
        String tampered = ciphertext.substring(0, ciphertext.length() - 4) + "abcd";

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(CredentialDecryptionException.class);
    }

    @Test
    void wrongKeyFailsDecryption() {
        CredentialEncryptionService encryptingService = serviceWithKey(randomKey());
        CredentialEncryptionService decryptingService = serviceWithKey(randomKey());
        String ciphertext = encryptingService.encrypt("super-secret-access-token");

        assertThatThrownBy(() -> decryptingService.decrypt(ciphertext))
                .isInstanceOf(CredentialDecryptionException.class);
    }

    @Test
    void malformedStoredValueFailsCleanly() {
        CredentialEncryptionService service = serviceWithKey(randomKey());

        assertThatThrownBy(() -> service.decrypt("not-a-valid-token"))
                .isInstanceOf(CredentialDecryptionException.class);
    }

    private CredentialEncryptionService serviceWithKey(String base64Key) {
        SocialCredentialEncryptionProperties properties = new SocialCredentialEncryptionProperties();
        properties.setEncryptionKey(base64Key);
        return new CredentialEncryptionService(properties);
    }

    private String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
