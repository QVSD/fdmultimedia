CREATE TABLE content_sources (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name               TEXT NOT NULL,
    description        TEXT,
    type               TEXT NOT NULL DEFAULT 'MEDIA_LIBRARY',
    status             TEXT NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT content_sources_type_valid CHECK (type IN ('MEDIA_LIBRARY')),
    CONSTRAINT content_sources_status_valid CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT content_sources_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX content_sources_workspace_idx ON content_sources (workspace_id, created_at DESC);

-- Explicit, controlled membership between a ContentSource and a workspace
-- MediaAsset — never a URL, never an arbitrary external reference. An asset
-- appears in a given source at most once; addedAt establishes the ordering
-- OLDEST_UNPROCESSED/NEWEST_UNPROCESSED select over.
CREATE TABLE content_source_assets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content_source_id  UUID NOT NULL REFERENCES content_sources(id) ON DELETE CASCADE,
    media_asset_id     UUID NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    added_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by_user_id   UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    CONSTRAINT content_source_assets_unique UNIQUE (content_source_id, media_asset_id)
);

CREATE INDEX content_source_assets_source_idx ON content_source_assets (content_source_id, added_at ASC, id ASC);
CREATE INDEX content_source_assets_asset_idx ON content_source_assets (media_asset_id);

-- Robot dynamic source support: a Robot now carries either a fixed
-- source_asset_id (EXISTING_ASSET, unchanged Phase 11C behavior) or a
-- content_source_id + selection_policy (CONTENT_SOURCE, new in Phase 11D) —
-- never both, enforced below exactly like the existing autonomy-mode CHECK
-- constraints from V18.
ALTER TABLE robots ALTER COLUMN source_asset_id DROP NOT NULL;
ALTER TABLE robots ADD COLUMN source_policy TEXT NOT NULL DEFAULT 'EXISTING_ASSET';
ALTER TABLE robots ADD COLUMN content_source_id UUID REFERENCES content_sources(id) ON DELETE RESTRICT;
ALTER TABLE robots ADD COLUMN selection_policy TEXT;

ALTER TABLE robots ADD CONSTRAINT robots_source_policy_valid
    CHECK (source_policy IN ('EXISTING_ASSET', 'CONTENT_SOURCE'));
ALTER TABLE robots ADD CONSTRAINT robots_selection_policy_valid
    CHECK (selection_policy IS NULL OR selection_policy IN ('OLDEST_UNPROCESSED', 'NEWEST_UNPROCESSED'));
ALTER TABLE robots ADD CONSTRAINT robots_source_config_matches_policy CHECK (
    (source_policy = 'EXISTING_ASSET' AND source_asset_id IS NOT NULL
        AND content_source_id IS NULL AND selection_policy IS NULL)
    OR
    (source_policy = 'CONTENT_SOURCE' AND source_asset_id IS NULL
        AND content_source_id IS NOT NULL AND selection_policy IS NOT NULL)
);

CREATE INDEX robots_content_source_idx ON robots (content_source_id) WHERE content_source_id IS NOT NULL;

-- RobotRun: source_asset_id becomes nullable for the NO_ELIGIBLE_SOURCE
-- terminal outcome (a real, auditable run that simply found nothing to do).
-- content_source_id/selection_policy are a snapshot of what the Robot was
-- configured with at run-start time, mirroring every other RobotRun
-- provenance field — never re-read from the live Robot afterward.
ALTER TABLE robot_runs ALTER COLUMN source_asset_id DROP NOT NULL;
ALTER TABLE robot_runs ADD COLUMN content_source_id UUID REFERENCES content_sources(id) ON DELETE SET NULL;
ALTER TABLE robot_runs ADD COLUMN selection_policy TEXT;
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_selection_policy_valid
    CHECK (selection_policy IS NULL OR selection_policy IN ('OLDEST_UNPROCESSED', 'NEWEST_UNPROCESSED'));

-- Defense-in-depth duplicate-consumption guard: the SAME Robot can never
-- dynamically select the SAME MediaAsset twice (regardless of that earlier
-- run's outcome — a failed run still permanently consumes the asset for
-- that Robot, see RobotSourceSelectionService). Scoped to
-- content_source_id IS NOT NULL so it never touches Phase 11C's existing
-- EXISTING_ASSET rows, which intentionally may retry the same fixed asset
-- after a failure and are guarded by SOURCE_ALREADY_PROCESSED (SUCCEEDED
-- only) exactly as before. A different Robot sharing the same ContentSource
-- may still select the same asset — consumption is per-Robot by design.
CREATE UNIQUE INDEX robot_runs_one_dynamic_selection_per_asset
    ON robot_runs (robot_id, source_asset_id)
    WHERE content_source_id IS NOT NULL;

CREATE INDEX robot_runs_content_source_idx ON robot_runs (content_source_id) WHERE content_source_id IS NOT NULL;
