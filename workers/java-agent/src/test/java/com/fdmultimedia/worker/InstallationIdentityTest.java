package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationIdentityTest {

    @TempDir
    private Path tempDir;

    @Test
    void createsAndReusesInstallationIdentifier() throws Exception {
        Path identityFile = tempDir.resolve("worker-id");

        String created = InstallationIdentity.loadOrCreate(identityFile);
        String reused = InstallationIdentity.loadOrCreate(identityFile);

        UUID.fromString(created);
        assertEquals(created, reused);
        assertEquals(created, Files.readString(identityFile).trim());
    }

    @Test
    void rejectsCorruptIdentityFile() throws Exception {
        Path identityFile = tempDir.resolve("worker-id");
        Files.writeString(identityFile, "not-a-uuid");

        assertThrows(IllegalArgumentException.class, () -> InstallationIdentity.loadOrCreate(identityFile));
    }
}
