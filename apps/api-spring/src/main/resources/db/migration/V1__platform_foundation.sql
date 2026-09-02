-- Phase 1 foundation migration.
--
-- This does not model the application's domain schema yet (users, workspaces,
-- robots, jobs, etc. come in later phases). It only proves that Flyway is
-- wired up correctly against PostgreSQL and leaves a marker row recording
-- when the platform's schema history began.

CREATE TABLE platform_foundation (
    id          SMALLINT PRIMARY KEY DEFAULT 1,
    established_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes       TEXT NOT NULL,
    CONSTRAINT platform_foundation_singleton CHECK (id = 1)
);

INSERT INTO platform_foundation (id, notes)
VALUES (1, 'Phase 1: project foundation. Flyway-managed schema history starts here.');
