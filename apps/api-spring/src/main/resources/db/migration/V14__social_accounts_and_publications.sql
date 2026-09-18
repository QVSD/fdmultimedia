ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA', 'CREATE_CLIP', 'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS', 'TRANSCRIBE_MEDIA', 'PUBLISH_MEDIA'
));

CREATE TABLE social_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    platform            TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    external_account_id TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id  UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT social_accounts_platform_valid CHECK (platform IN ('TEST', 'INSTAGRAM', 'TIKTOK')),
    CONSTRAINT social_accounts_status_valid CHECK (status IN ('ACTIVE', 'DISCONNECTED', 'ERROR')),
    CONSTRAINT social_accounts_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE INDEX social_accounts_workspace_created_idx ON social_accounts (workspace_id, created_at DESC);

-- Publishing destination/credential boundary: this table intentionally has no
-- credential/token columns. A real provider's access token, when Phase 10B
-- adds one, must live in a separate, secret-managed store keyed by
-- social_accounts.id — never on this row, never in a job payload, and never
-- returned to the browser. See docs/ARCHITECTURE.md.

CREATE TABLE publications (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    asset_id               UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    social_account_id      UUID NOT NULL REFERENCES social_accounts(id) ON DELETE RESTRICT,
    status                 TEXT NOT NULL DEFAULT 'PENDING',
    job_id                 UUID REFERENCES jobs(id) ON DELETE SET NULL,
    caption                TEXT,
    provider_request_id    TEXT,
    provider_publication_id TEXT,
    created_by_user_id     UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at           TIMESTAMPTZ,
    failure_code           TEXT,
    failure_message        TEXT,
    CONSTRAINT publications_status_valid CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'CANCELLED')),
    CONSTRAINT publications_caption_length CHECK (caption IS NULL OR char_length(caption) <= 2200),
    CONSTRAINT publications_provider_request_id_length CHECK (provider_request_id IS NULL OR char_length(provider_request_id) <= 200),
    CONSTRAINT publications_provider_publication_id_length CHECK (provider_publication_id IS NULL OR char_length(provider_publication_id) <= 200),
    CONSTRAINT publications_failure_code_not_blank CHECK (failure_code IS NULL OR btrim(failure_code) <> ''),
    CONSTRAINT publications_failure_message_not_blank CHECK (failure_message IS NULL OR btrim(failure_message) <> '')
);

CREATE INDEX publications_workspace_created_idx ON publications (workspace_id, created_at DESC);
CREATE INDEX publications_asset_idx ON publications (asset_id);
CREATE INDEX publications_job_idx ON publications (job_id);
CREATE INDEX publications_provider_publication_idx ON publications (provider_publication_id) WHERE provider_publication_id IS NOT NULL;

CREATE TABLE publishing_attempts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id          UUID NOT NULL REFERENCES publications(id) ON DELETE CASCADE,
    job_id                  UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    job_attempt             INTEGER NOT NULL,
    worker_id               UUID REFERENCES workers(id) ON DELETE SET NULL,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ NOT NULL,
    outcome                 TEXT NOT NULL,
    provider_request_id     TEXT,
    provider_publication_id TEXT,
    error_code              TEXT,
    error_message           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT publishing_attempts_outcome_valid CHECK (outcome IN ('SUCCEEDED', 'RETRYABLE_FAILED', 'FAILED')),
    CONSTRAINT publishing_attempts_job_attempt_positive CHECK (job_attempt > 0),
    CONSTRAINT publishing_attempts_provider_request_id_length CHECK (provider_request_id IS NULL OR char_length(provider_request_id) <= 200),
    CONSTRAINT publishing_attempts_provider_publication_id_length CHECK (provider_publication_id IS NULL OR char_length(provider_publication_id) <= 200),
    CONSTRAINT publishing_attempts_error_message_length CHECK (error_message IS NULL OR char_length(error_message) <= 2000),
    CONSTRAINT publishing_attempts_unique_job_attempt UNIQUE (publication_id, job_attempt)
);

CREATE INDEX publishing_attempts_publication_idx ON publishing_attempts (publication_id, created_at DESC);
