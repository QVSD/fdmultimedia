ALTER TABLE content_drafts ADD COLUMN applied_content_suggestion_id UUID
    REFERENCES content_suggestions(id) ON DELETE SET NULL;
ALTER TABLE publish_schedules ADD COLUMN applied_content_suggestion_id_snapshot UUID
    REFERENCES content_suggestions(id) ON DELETE SET NULL;

CREATE TABLE publication_attributions (
    publication_id UUID PRIMARY KEY REFERENCES publications(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    content_draft_id UUID,
    publish_schedule_id UUID,
    robot_run_id UUID,
    robot_id UUID,
    robot_name_snapshot TEXT,
    content_source_id UUID,
    source_media_asset_id UUID,
    final_media_asset_id UUID NOT NULL,
    applied_content_suggestion_id UUID,
    suggestion_origin TEXT,
    persona_id UUID,
    persona_name_snapshot TEXT,
    ai_provider TEXT,
    ai_model TEXT,
    prompt_version TEXT,
    ai_policy TEXT,
    robot_autonomy_mode TEXT,
    source_selection_policy TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT publication_attributions_suggestion_origin_valid
        CHECK (suggestion_origin IS NULL OR suggestion_origin IN ('MANUAL', 'ROBOT'))
);
CREATE INDEX publication_attributions_workspace_idx ON publication_attributions(workspace_id);

-- Historical publications predate applied-suggestion tracking. Preserve only
-- facts already stored, without pretending mutable Robot/Persona names are
-- publication-time snapshots.
INSERT INTO publication_attributions (
    publication_id, workspace_id, content_draft_id, robot_run_id, robot_id,
    content_source_id, source_media_asset_id, final_media_asset_id, created_at)
SELECT p.id, p.workspace_id, p.content_draft_id, d.robot_run_id, r.robot_id,
       r.content_source_id, COALESCE(d.source_asset_id, p.asset_id), p.asset_id, p.created_at
FROM publications p
LEFT JOIN content_drafts d ON d.id = p.content_draft_id
LEFT JOIN robot_runs r ON r.id = d.robot_run_id;

CREATE TABLE publication_analytics_states (
    publication_id UUID PRIMARY KEY REFERENCES publications(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    next_bucket INTEGER NOT NULL DEFAULT 0,
    next_collection_at TIMESTAMPTZ,
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    failure_code TEXT,
    failure_message TEXT,
    completed_at TIMESTAMPTZ,
    CONSTRAINT publication_analytics_next_bucket_valid CHECK (next_bucket >= 0),
    CONSTRAINT publication_analytics_claim_pair CHECK
        ((claim_token IS NULL AND claim_expires_at IS NULL)
         OR (claim_token IS NOT NULL AND claim_expires_at IS NOT NULL))
);
CREATE INDEX publication_analytics_due_idx
    ON publication_analytics_states(next_collection_at)
    WHERE next_collection_at IS NOT NULL AND completed_at IS NULL;

CREATE TABLE publication_analytics_snapshots (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    publication_id UUID NOT NULL REFERENCES publications(id) ON DELETE CASCADE,
    social_account_id UUID NOT NULL REFERENCES social_accounts(id) ON DELETE RESTRICT,
    provider TEXT NOT NULL,
    bucket_key TEXT NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    provider_observed_at TIMESTAMPTZ,
    publication_age_seconds BIGINT NOT NULL CHECK (publication_age_seconds >= 0),
    views BIGINT CHECK (views >= 0),
    reach BIGINT CHECK (reach >= 0),
    likes BIGINT CHECK (likes >= 0),
    comments BIGINT CHECK (comments >= 0),
    shares BIGINT CHECK (shares >= 0),
    saves BIGINT CHECK (saves >= 0),
    total_interactions BIGINT CHECK (total_interactions >= 0),
    watch_time_ms BIGINT CHECK (watch_time_ms >= 0),
    average_watch_time_ms BIGINT CHECK (average_watch_time_ms >= 0),
    provider_metric_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT publication_analytics_one_bucket UNIQUE (publication_id, bucket_key)
);
CREATE INDEX publication_analytics_history_idx
    ON publication_analytics_snapshots(publication_id, collected_at DESC);
CREATE INDEX publication_analytics_workspace_idx
    ON publication_analytics_snapshots(workspace_id, collected_at DESC);

-- Existing published posts enter the bounded due queue. The scheduler's batch
-- limit prevents a migration-time thundering herd.
INSERT INTO publication_analytics_states(publication_id, workspace_id, next_bucket, next_collection_at)
SELECT id, workspace_id, 0, now() FROM publications WHERE status = 'PUBLISHED';
