CREATE TABLE content_suggestions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    content_draft_id    UUID NOT NULL REFERENCES content_drafts(id) ON DELETE CASCADE,
    robot_run_id        UUID,
    generation_job_id   UUID NOT NULL REFERENCES jobs(id) ON DELETE RESTRICT,
    type                TEXT NOT NULL DEFAULT 'SOCIAL_COPY',
    status              TEXT NOT NULL DEFAULT 'PENDING',
    provider            TEXT NOT NULL,
    model               TEXT NOT NULL,
    prompt_version      TEXT NOT NULL,
    language            TEXT NOT NULL,
    tone                TEXT NOT NULL,
    -- The exact prompt sent to the provider, frozen at generation time — this
    -- is what makes suggestion output explainable/reproducible later. Never
    -- exposed through any human API response and never logged; it is a
    -- durable business record, the same trust level as transcript_segments.text.
    prompt_text         TEXT NOT NULL,
    hook                TEXT,
    caption             TEXT,
    short_title         TEXT,
    -- Deterministic hash over the generation inputs (draft title/caption at
    -- generation time, source asset, highlight candidate, language, tone,
    -- prompt version, provider/model) — recomputed fresh at Apply time to
    -- detect a human edit made to the Draft since generation.
    input_fingerprint   TEXT NOT NULL,
    transcript_used     BOOLEAN NOT NULL DEFAULT FALSE,
    transcript_id       UUID,
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,
    total_tokens        INTEGER,
    latency_ms          BIGINT,
    failure_code        TEXT,
    failure_message     TEXT,
    created_by_user_id  UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    applied_at          TIMESTAMPTZ,
    applied_by_user_id  UUID,
    CONSTRAINT content_suggestions_type_valid CHECK (type IN ('SOCIAL_COPY')),
    CONSTRAINT content_suggestions_status_valid CHECK (status IN (
        'PENDING', 'GENERATING', 'READY', 'FAILED', 'APPLIED', 'DISCARDED'
    )),
    CONSTRAINT content_suggestions_language_valid CHECK (language IN ('AUTO', 'ENGLISH', 'ROMANIAN')),
    CONSTRAINT content_suggestions_tone_valid CHECK (tone IN ('NEUTRAL', 'INFORMATIVE', 'CASUAL', 'ENERGETIC')),
    CONSTRAINT content_suggestions_prompt_tokens_nonneg CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT content_suggestions_completion_tokens_nonneg CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT content_suggestions_total_tokens_nonneg CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT content_suggestions_latency_nonneg CHECK (latency_ms IS NULL OR latency_ms >= 0),
    -- One logical ContentSuggestion maps to exactly one Job; retries are
    -- attempts of that same Job, never a second Job for the same suggestion.
    CONSTRAINT content_suggestions_generation_job_unique UNIQUE (generation_job_id)
);

CREATE INDEX content_suggestions_draft_created_idx ON content_suggestions (content_draft_id, created_at DESC);
CREATE INDEX content_suggestions_workspace_created_idx ON content_suggestions (workspace_id, created_at DESC);
CREATE INDEX content_suggestions_active_idx ON content_suggestions (status) WHERE status IN ('PENDING', 'GENERATING');

-- Structured hashtags (never a raw delimited string) in stable generated order.
CREATE TABLE content_suggestion_hashtags (
    content_suggestion_id UUID NOT NULL REFERENCES content_suggestions(id) ON DELETE CASCADE,
    position              INTEGER NOT NULL,
    tag                   TEXT NOT NULL,
    PRIMARY KEY (content_suggestion_id, position)
);

-- Every phase that introduces a new JobType re-declares this constraint
-- (see V5/V6/V7/V8/V9/V10/V14) — GENERATE_SOCIAL_COPY joins the existing list.
ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA', 'CREATE_CLIP', 'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS', 'TRANSCRIBE_MEDIA', 'PUBLISH_MEDIA', 'GENERATE_SOCIAL_COPY'
));
