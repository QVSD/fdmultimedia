package com.fdmultimedia.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class InstallationIdentity {

    private InstallationIdentity() {
    }

    static String loadOrCreate(Path identityFile) throws IOException {
        if (Files.exists(identityFile)) {
            String existing = Files.readString(identityFile, StandardCharsets.UTF_8).trim();
            UUID.fromString(existing);
            return existing;
        }

        Path parent = identityFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String generated = UUID.randomUUID().toString();
        Files.writeString(identityFile, generated + System.lineSeparator(), StandardCharsets.UTF_8);
        return generated;
    }
}
