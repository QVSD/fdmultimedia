CREATE TABLE scheduling_decisions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    worker_id           UUID NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    job_type            TEXT NOT NULL,
    attempt              INTEGER NOT NULL,
    policy               TEXT NOT NULL,
    decision             TEXT NOT NULL,
    suitability_score   DOUBLE PRECISION,
    telemetry_fresh     BOOLEAN NOT NULL,
    fallback_used       BOOLEAN NOT NULL,
    starvation_override BOOLEAN NOT NULL,
    reason_codes        JSONB NOT NULL,
    active_jobs         INTEGER,
    max_active_jobs     INTEGER NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT scheduling_decisions_attempt_positive CHECK (attempt > 0),
    CONSTRAINT scheduling_decisions_decision_valid CHECK (decision = 'CLAIMED'),
    CONSTRAINT scheduling_decisions_active_jobs_valid CHECK (active_jobs IS NULL OR active_jobs >= 0),
    CONSTRAINT scheduling_decisions_max_active_jobs_valid CHECK (max_active_jobs > 0),
    CONSTRAINT scheduling_decisions_job_attempt_unique UNIQUE (job_id, attempt)
);

CREATE INDEX scheduling_decisions_workspace_created_idx
    ON scheduling_decisions (workspace_id, created_at DESC);
CREATE INDEX scheduling_decisions_worker_created_idx
    ON scheduling_decisions (worker_id, created_at DESC);

