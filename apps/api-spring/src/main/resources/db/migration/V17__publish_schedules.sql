CREATE TABLE publish_schedules (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    content_draft_id     UUID NOT NULL REFERENCES content_drafts(id) ON DELETE RESTRICT,
    media_asset_id       UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    social_account_id    UUID NOT NULL REFERENCES social_accounts(id) ON DELETE RESTRICT,
    caption_snapshot     TEXT,
    scheduled_for        TIMESTAMPTZ NOT NULL,
    status               TEXT NOT NULL DEFAULT 'SCHEDULED',
    publication_id       UUID REFERENCES publications(id) ON DELETE SET NULL,
    created_by_user_id   UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at        TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    failure_code         TEXT,
    failure_message      TEXT,
    CONSTRAINT publish_schedules_status_valid CHECK (status IN ('SCHEDULED', 'DISPATCHED', 'CANCELLED', 'FAILED')),
    CONSTRAINT publish_schedules_caption_length CHECK (caption_snapshot IS NULL OR char_length(caption_snapshot) <= 2200),
    CONSTRAINT publish_schedules_failure_code_not_blank CHECK (failure_code IS NULL OR btrim(failure_code) <> ''),
    CONSTRAINT publish_schedules_failure_message_not_blank CHECK (failure_message IS NULL OR btrim(failure_message) <> ''),
    -- At most one dispatched Publication per schedule, enforced at the
    -- database level as defense in depth alongside the row-locked atomic
    -- dispatch transaction (see PublishScheduleDispatcher).
    CONSTRAINT publish_schedules_publication_unique UNIQUE (publication_id)
);

-- The dispatcher's due-schedule claim query scans across all workspaces
-- (it is a background process, not a workspace-scoped request), so this
-- index is intentionally not workspace-prefixed.
CREATE INDEX publish_schedules_status_scheduled_for_idx ON publish_schedules (status, scheduled_for);
CREATE INDEX publish_schedules_workspace_scheduled_for_idx ON publish_schedules (workspace_id, scheduled_for);
CREATE INDEX publish_schedules_content_draft_idx ON publish_schedules (content_draft_id);
