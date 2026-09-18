CREATE TABLE robots (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id              UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name                      TEXT NOT NULL,
    description               TEXT,
    status                    TEXT NOT NULL DEFAULT 'ACTIVE',
    autonomy_mode             TEXT NOT NULL,
    highlight_strategy        TEXT NOT NULL DEFAULT 'TOP_HIGHLIGHT',
    source_asset_id           UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    target_social_account_id  UUID REFERENCES social_accounts(id) ON DELETE RESTRICT,
    cadence_type              TEXT NOT NULL,
    cadence_interval_hours    INTEGER,
    schedule_delay_minutes    INTEGER,
    max_runs_per_day          INTEGER NOT NULL DEFAULT 1,
    next_run_at               TIMESTAMPTZ,
    last_run_at               TIMESTAMPTZ,
    created_by_user_id        UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT robots_status_valid CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED')),
    CONSTRAINT robots_autonomy_mode_valid CHECK (autonomy_mode IN ('DRAFT_ONLY', 'REVIEW_REQUIRED', 'AUTO_SCHEDULE')),
    CONSTRAINT robots_highlight_strategy_valid CHECK (highlight_strategy IN ('TOP_HIGHLIGHT')),
    CONSTRAINT robots_cadence_type_valid CHECK (cadence_type IN ('MANUAL_ONLY', 'INTERVAL')),
    CONSTRAINT robots_cadence_interval_bounds CHECK (cadence_interval_hours IS NULL OR (cadence_interval_hours BETWEEN 1 AND 168)),
    CONSTRAINT robots_schedule_delay_bounds CHECK (schedule_delay_minutes IS NULL OR (schedule_delay_minutes BETWEEN 1 AND 10080)),
    CONSTRAINT robots_max_runs_per_day_bounds CHECK (max_runs_per_day BETWEEN 1 AND 24),
    CONSTRAINT robots_name_not_blank CHECK (btrim(name) <> ''),
    -- INTERVAL cadence must carry an interval; MANUAL_ONLY must not (avoids a
    -- Robot that looks scheduled but has no interval to compute nextRunAt from).
    CONSTRAINT robots_cadence_interval_matches_type CHECK (
        (cadence_type = 'INTERVAL' AND cadence_interval_hours IS NOT NULL)
        OR (cadence_type = 'MANUAL_ONLY' AND cadence_interval_hours IS NULL)
    ),
    -- AUTO_SCHEDULE must carry a delay and a target account; the other modes
    -- don't require either at the database level (DRAFT_ONLY needs neither;
    -- REVIEW_REQUIRED needs an account but not a delay).
    CONSTRAINT robots_auto_schedule_requires_delay CHECK (
        autonomy_mode <> 'AUTO_SCHEDULE' OR schedule_delay_minutes IS NOT NULL
    )
);

CREATE INDEX robots_workspace_idx ON robots (workspace_id, created_at DESC);
CREATE INDEX robots_due_idx ON robots (next_run_at) WHERE status = 'ACTIVE' AND cadence_type = 'INTERVAL';
CREATE INDEX robots_source_asset_idx ON robots (source_asset_id);

CREATE TABLE robot_runs (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id               UUID NOT NULL REFERENCES robots(id) ON DELETE CASCADE,
    trigger_type           TEXT NOT NULL,
    status                 TEXT NOT NULL,
    started_at             TIMESTAMPTZ NOT NULL,
    finished_at            TIMESTAMPTZ,
    source_asset_id        UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    highlight_analysis_id  UUID REFERENCES highlight_analyses(id) ON DELETE SET NULL,
    highlight_candidate_id UUID REFERENCES highlight_candidates(id) ON DELETE SET NULL,
    content_draft_id       UUID REFERENCES content_drafts(id) ON DELETE SET NULL,
    publish_schedule_id    UUID REFERENCES publish_schedules(id) ON DELETE SET NULL,
    failure_code           TEXT,
    failure_message        TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT robot_runs_trigger_type_valid CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT robot_runs_status_valid CHECK (status IN (
        'RUNNING', 'WAITING_FOR_DRAFT', 'WAITING_FOR_REVIEW', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT robot_runs_failure_code_not_blank CHECK (failure_code IS NULL OR btrim(failure_code) <> ''),
    CONSTRAINT robot_runs_publish_schedule_unique UNIQUE (publish_schedule_id)
);

-- Enforces "at most one non-terminal RobotRun per Robot" at the database
-- level — a manual Run Now racing the scheduled dispatcher for the same
-- Robot can insert at most one row before this index rejects the second.
CREATE UNIQUE INDEX robot_runs_one_active_per_robot
    ON robot_runs (robot_id)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

CREATE INDEX robot_runs_robot_created_idx ON robot_runs (robot_id, created_at DESC);
CREATE INDEX robot_runs_workspace_created_idx ON robot_runs (workspace_id, created_at DESC);
CREATE INDEX robot_runs_nonterminal_idx ON robot_runs (status) WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
CREATE INDEX robot_runs_source_asset_idx ON robot_runs (robot_id, source_asset_id);

CREATE TABLE robot_approvals (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_run_id           UUID NOT NULL UNIQUE REFERENCES robot_runs(id) ON DELETE CASCADE,
    content_draft_id       UUID NOT NULL REFERENCES content_drafts(id) ON DELETE RESTRICT,
    social_account_id      UUID NOT NULL REFERENCES social_accounts(id) ON DELETE RESTRICT,
    proposed_scheduled_for TIMESTAMPTZ,
    status                 TEXT NOT NULL DEFAULT 'PENDING',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at             TIMESTAMPTZ,
    decided_by_user_id     UUID REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT robot_approvals_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX robot_approvals_workspace_status_idx ON robot_approvals (workspace_id, status, created_at DESC);

-- Draft provenance: which RobotRun (if any) created this Draft. Plain
-- nullable FK, no reverse relationship needed on ContentDraft's own domain
-- logic — mirrors publications.content_draft_id from Phase 11A/11B.
ALTER TABLE content_drafts ADD COLUMN robot_run_id UUID REFERENCES robot_runs(id) ON DELETE SET NULL;
CREATE INDEX content_drafts_robot_run_idx ON content_drafts (robot_run_id) WHERE robot_run_id IS NOT NULL;
