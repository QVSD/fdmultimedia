package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.accounts.crypto.CredentialEncryptionService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only path allowed to touch a provider credential's plaintext value.
 * Callers get the plaintext for exactly as long as one provider HTTP call
 * needs it (e.g. inside {@code InstagramGraphClient}); nothing here exposes a
 * bulk "all plaintext credentials" accessor, and the plaintext is never
 * logged, persisted outside this narrow flow, or sent to a Worker.
 */
@Service
public class SocialCredentialService {

    private final SocialAccountCredentialRepository credentials;
    private final CredentialEncryptionService encryptionService;
    private final Clock clock;

    public SocialCredentialService(
            SocialAccountCredentialRepository credentials,
            CredentialEncryptionService encryptionService,
            Clock clock) {
        this.credentials = credentials;
        this.encryptionService = encryptionService;
        this.clock = clock;
    }

    @Transactional
    public void store(SocialAccount account, String credentialType, String plaintextAccessToken, Instant tokenExpiresAt, String scopes) {
        Instant now = Instant.now(clock);
        String ciphertext = encryptionService.encrypt(plaintextAccessToken);
        credentials.findBySocialAccount(account)
                .ifPresentOrElse(
                        existing -> existing.replace(ciphertext, tokenExpiresAt, scopes, now),
                        () -> credentials.save(new SocialAccountCredential(account, credentialType, ciphertext, tokenExpiresAt, scopes, now)));
    }

    @Transactional(readOnly = true)
    public String decryptAccessToken(SocialAccount account) {
        SocialAccountCredential credential = credentials.findBySocialAccount(account)
                .orElseThrow(() -> new CredentialUnavailableException("No credential stored for this account"));
        if (credential.isExpired(Instant.now(clock))) {
            throw new CredentialUnavailableException("Credential expired");
        }
        return encryptionService.decrypt(credential.getEncryptedAccessToken());
    }

    @Transactional(readOnly = true)
    public SocialCredentialMetadata metadataFor(SocialAccount account) {
        return credentials.findBySocialAccount(account)
                .map(credential -> new SocialCredentialMetadata(
                        true,
                        credential.getTokenExpiresAt(),
                        credential.isExpired(Instant.now(clock)),
                        credential.getScopes(),
                        credential.getLastValidatedAt()))
                .orElseGet(SocialCredentialMetadata::absent);
    }

    @Transactional
    public void remove(SocialAccount account) {
        credentials.deleteBySocialAccount(account);
    }
}
