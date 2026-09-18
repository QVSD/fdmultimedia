package com.fdmultimedia.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.crypto.CredentialEncryptionService;
import com.fdmultimedia.api.accounts.crypto.SocialCredentialEncryptionProperties;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SocialCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final SocialAccountCredentialRepository credentials = mock(SocialAccountCredentialRepository.class);
    private final CredentialEncryptionService encryptionService = realEncryptionService();
    private final SocialCredentialService service =
            new SocialCredentialService(credentials, encryptionService, Clock.fixed(NOW, ZoneOffset.UTC));

    private SocialAccount account;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace("FD Multimedia", "fdm");
        AppUser owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        account = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
    }

    @Test
    void storesEncryptedNeverPlaintext() {
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.empty());
        ArgumentCaptor<SocialAccountCredential> captor = ArgumentCaptor.forClass(SocialAccountCredential.class);
        when(credentials.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.store(account, "INSTAGRAM_OAUTH2", "plaintext-access-token", NOW.plusSeconds(3600), "scope-a,scope-b");

        String stored = captor.getValue().getEncryptedAccessToken();
        assertThat(stored).doesNotContain("plaintext-access-token");
        assertThat(encryptionService.decrypt(stored)).isEqualTo("plaintext-access-token");
    }

    @Test
    void decryptsBackToOriginalPlaintext() {
        SocialAccountCredential credential = new SocialAccountCredential(
                account, "INSTAGRAM_OAUTH2", encryptionService.encrypt("real-token-value"), NOW.plusSeconds(3600), "scopes", NOW);
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.of(credential));

        assertThat(service.decryptAccessToken(account)).isEqualTo("real-token-value");
    }

    @Test
    void refusesToDecryptExpiredCredential() {
        SocialAccountCredential credential = new SocialAccountCredential(
                account, "INSTAGRAM_OAUTH2", encryptionService.encrypt("real-token-value"), NOW.minusSeconds(1), "scopes", NOW);
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.decryptAccessToken(account))
                .isInstanceOf(CredentialUnavailableException.class);
    }

    @Test
    void refusesToDecryptMissingCredential() {
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decryptAccessToken(account))
                .isInstanceOf(CredentialUnavailableException.class);
    }

    @Test
    void metadataNeverExposesTheToken() {
        SocialAccountCredential credential = new SocialAccountCredential(
                account, "INSTAGRAM_OAUTH2", encryptionService.encrypt("real-token-value"), NOW.plusSeconds(3600), "scope-a", NOW);
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.of(credential));

        SocialCredentialMetadata metadata = service.metadataFor(account);

        assertThat(metadata.present()).isTrue();
        assertThat(metadata.expired()).isFalse();
        assertThat(metadata.scopes()).isEqualTo("scope-a");
        assertThat(metadata.toString()).doesNotContain("real-token-value");
    }

    @Test
    void metadataForAbsentCredentialReportsNotPresent() {
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.empty());

        assertThat(service.metadataFor(account)).isEqualTo(SocialCredentialMetadata.absent());
    }

    @Test
    void replacingAnExistingCredentialUpdatesInPlaceRatherThanDuplicating() {
        SocialAccountCredential existing = new SocialAccountCredential(
                account, "INSTAGRAM_OAUTH2", encryptionService.encrypt("old-token"), NOW, "old-scope", NOW);
        when(credentials.findBySocialAccount(account)).thenReturn(Optional.of(existing));

        service.store(account, "INSTAGRAM_OAUTH2", "new-token", NOW.plusSeconds(7200), "new-scope");

        assertThat(encryptionService.decrypt(existing.getEncryptedAccessToken())).isEqualTo("new-token");
        assertThat(existing.getScopes()).isEqualTo("new-scope");
    }

    private CredentialEncryptionService realEncryptionService() {
        SocialCredentialEncryptionProperties properties = new SocialCredentialEncryptionProperties();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        return new CredentialEncryptionService(properties);
    }
}
