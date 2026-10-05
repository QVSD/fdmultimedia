package com.fdmultimedia.api.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecoveryScriptContractTest {
    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void backupUsesCustomFormatAndExcludesActiveSessionData() throws Exception {
        String script = Files.readString(ROOT.resolve("scripts/backup-postgres.ps1"));
        assertThat(script).contains("[Parameter(Mandatory = $true)]", "pg_dump", "-Fc");
        assertThat(script).contains("--exclude-table-data=public.spring_session");
        assertThat(script).doesNotContain("POSTGRES_PASSWORD");
    }

    @Test
    void restoreRequiresExplicitConfirmedTargetAndInvalidatesSessions() throws Exception {
        String script = Files.readString(ROOT.resolve("scripts/restore-postgres.ps1"));
        assertThat(script).contains("TargetDatabase", "ConfirmTargetDatabase", "Refusing to restore over POSTGRES_DB");
        assertThat(script).contains("pg_restore", "TRUNCATE spring_session_attributes, spring_session");
        assertThat(script).doesNotContain("POSTGRES_PASSWORD");
    }
}
