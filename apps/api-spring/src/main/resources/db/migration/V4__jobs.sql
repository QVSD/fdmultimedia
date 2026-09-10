CREATE TABLE jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    type                TEXT NOT NULL,
    status              TEXT NOT NULL,
    payload             JSONB NOT NULL,
    result              JSONB,
    error_code          TEXT,
    error_message       TEXT,
    assigned_worker_id  UUID REFERENCES workers(id) ON DELETE SET NULL,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    max_attempts        INTEGER NOT NULL DEFAULT 3,
    queued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at         TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    lease_expires_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT jobs_type_valid CHECK (type IN ('SYSTEM_TEST')),
    CONSTRAINT jobs_status_valid CHECK (status IN ('QUEUED', 'ASSIGNED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT jobs_attempt_count_non_negative CHECK (attempt_count >= 0),
    CONSTRAINT jobs_max_attempts_positive CHECK (max_attempts > 0),
    CONSTRAINT jobs_error_code_not_blank CHECK (error_code IS NULL OR btrim(error_code) <> ''),
    CONSTRAINT jobs_error_message_not_blank CHECK (error_message IS NULL OR btrim(error_message) <> '')
);

CREATE INDEX jobs_workspace_queued_at_idx ON jobs (workspace_id, queued_at DESC);
CREATE INDEX jobs_workspace_status_idx ON jobs (workspace_id, status);
CREATE INDEX jobs_queue_lookup_idx ON jobs (workspace_id, status, queued_at) WHERE status = 'QUEUED';
CREATE INDEX jobs_lease_recovery_idx ON jobs (status, lease_expires_at)
    WHERE status IN ('ASSIGNED', 'RUNNING');
CREATE INDEX jobs_assigned_worker_idx ON jobs (assigned_worker_id);
