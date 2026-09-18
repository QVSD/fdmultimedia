CREATE TABLE content_drafts (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_asset_id                 UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    media_asset_id                  UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    source_highlight_candidate_id   UUID REFERENCES highlight_candidates(id) ON DELETE SET NULL,
    title                           TEXT,
    caption                         TEXT,
    status                          TEXT NOT NULL DEFAULT 'DRAFT',
    workflow_stage                  TEXT NOT NULL,
    pending_job_id                  UUID REFERENCES jobs(id) ON DELETE SET NULL,
    failure_code                    TEXT,
    failure_message                 TEXT,
    created_by_user_id              UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at                    TIMESTAMPTZ,
    CONSTRAINT content_drafts_status_valid CHECK (status IN ('DRAFT', 'READY', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT content_drafts_workflow_stage_valid CHECK (workflow_stage IN ('CLIP_PENDING', 'VERTICAL_PENDING', 'READY')),
    CONSTRAINT content_drafts_title_length CHECK (title IS NULL OR char_length(title) <= 200),
    CONSTRAINT content_drafts_caption_length CHECK (caption IS NULL OR char_length(caption) <= 2200),
    CONSTRAINT content_drafts_failure_code_not_blank CHECK (failure_code IS NULL OR btrim(failure_code) <> ''),
    CONSTRAINT content_drafts_failure_message_not_blank CHECK (failure_message IS NULL OR btrim(failure_message) <> '')
);

CREATE INDEX content_drafts_workspace_created_idx ON content_drafts (workspace_id, created_at DESC);
CREATE INDEX content_drafts_source_asset_idx ON content_drafts (source_asset_id);
CREATE INDEX content_drafts_media_asset_idx ON content_drafts (media_asset_id);
CREATE INDEX content_drafts_source_candidate_idx ON content_drafts (source_highlight_candidate_id);

ALTER TABLE publications ADD COLUMN content_draft_id UUID REFERENCES content_drafts(id) ON DELETE SET NULL;
CREATE INDEX publications_content_draft_idx ON publications (content_draft_id) WHERE content_draft_id IS NOT NULL;
