package com.fdmultimedia.api.accounts.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Authenticated encryption (AES-256-GCM) for provider credential tokens at
 * rest. The key comes only from deployment configuration
 * ({@code SOCIAL_CREDENTIAL_ENCRYPTION_KEY}); this service never generates,
 * caches beyond the request, or persists a key. Every encryption uses a fresh
 * random 96-bit nonce, so the same plaintext never produces the same
 * ciphertext twice. GCM's authentication tag makes any tampering with the
 * ciphertext fail decryption (AEADBadTagException) rather than silently
 * returning corrupted plaintext.
 *
 * <p>Stored form is {@code "v1:" + base64(nonce || ciphertext+tag)} so the
 * format can change in a later version without breaking already-stored rows.
 */
@Service
public class CredentialEncryptionService {

    private static final String FORMAT_VERSION = "v1";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32;
    private static final byte[] ASSOCIATED_DATA = "social-credential:v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] keyBytes;

    public CredentialEncryptionService(SocialCredentialEncryptionProperties properties) {
        this.keyBytes = decodeKey(properties.getEncryptionKey());
    }

    /** True only when a syntactically valid key was configured. */
    public boolean isAvailable() {
        return keyBytes != null;
    }

    public String encrypt(String plaintext) {
        requireAvailable();
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return FORMAT_VERSION + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Credential encryption failed", ex);
        }
    }

    public String decrypt(String stored) {
        requireAvailable();
        int separator = stored.indexOf(':');
        if (separator < 0 || !FORMAT_VERSION.equals(stored.substring(0, separator))) {
            throw new CredentialDecryptionException("Unsupported credential ciphertext format");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored.substring(separator + 1));
        } catch (IllegalArgumentException ex) {
            throw new CredentialDecryptionException("Malformed credential ciphertext", ex);
        }
        if (combined.length <= NONCE_LENGTH_BYTES) {
            throw new CredentialDecryptionException("Malformed credential ciphertext");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_LENGTH_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(combined, NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new CredentialDecryptionException("Credential ciphertext failed authentication (tampered or wrong key)", ex);
        } catch (GeneralSecurityException ex) {
            throw new CredentialDecryptionException("Credential decryption failed", ex);
        }
    }

    private void requireAvailable() {
        if (keyBytes == null) {
            throw new IllegalStateException("SOCIAL_CREDENTIAL_ENCRYPTION_KEY is not configured");
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SOCIAL_CREDENTIAL_ENCRYPTION_KEY must be base64-encoded", ex);
        }
        if (decoded.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "SOCIAL_CREDENTIAL_ENCRYPTION_KEY must decode to exactly " + REQUIRED_KEY_LENGTH_BYTES + " bytes (AES-256)");
        }
        return decoded;
    }
}
