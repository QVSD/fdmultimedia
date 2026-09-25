ALTER TABLE robots DROP CONSTRAINT robots_highlight_strategy_valid;
ALTER TABLE robots ADD CONSTRAINT robots_highlight_strategy_valid
    CHECK (highlight_strategy IN ('TOP_HIGHLIGHT', 'TOP_DIVERSE_HIGHLIGHTS'));
ALTER TABLE robots ADD COLUMN highlight_count integer NOT NULL DEFAULT 1;
ALTER TABLE robots ADD COLUMN output_spacing_minutes integer NOT NULL DEFAULT 60;
ALTER TABLE robots ADD CONSTRAINT robots_highlight_count_valid CHECK (highlight_count BETWEEN 1 AND 5);
ALTER TABLE robots ADD CONSTRAINT robots_output_spacing_valid CHECK (output_spacing_minutes BETWEEN 1 AND 1440);

ALTER TABLE robot_runs ADD COLUMN highlight_strategy_snapshot text NOT NULL DEFAULT 'TOP_HIGHLIGHT';
ALTER TABLE robot_runs ADD COLUMN requested_output_count integer NOT NULL DEFAULT 1;
ALTER TABLE robot_runs ADD COLUMN actual_output_count integer;
ALTER TABLE robot_runs ADD COLUMN output_spacing_minutes_snapshot integer NOT NULL DEFAULT 60;
ALTER TABLE robot_runs ADD COLUMN highlight_selection_id uuid REFERENCES highlight_selections(id) ON DELETE SET NULL;
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_highlight_strategy_snapshot_valid
    CHECK (highlight_strategy_snapshot IN ('TOP_HIGHLIGHT', 'TOP_DIVERSE_HIGHLIGHTS'));
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_requested_output_count_valid CHECK (requested_output_count BETWEEN 1 AND 5);
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_actual_output_count_valid
    CHECK (actual_output_count IS NULL OR actual_output_count BETWEEN 0 AND requested_output_count);
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_output_spacing_valid CHECK (output_spacing_minutes_snapshot BETWEEN 1 AND 1440);
ALTER TABLE robot_runs DROP CONSTRAINT robot_runs_status_valid;
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_status_valid CHECK (status IN (
    'RUNNING', 'WAITING_FOR_DRAFT', 'WAITING_FOR_AI', 'WAITING_FOR_AI_REVIEW',
    'WAITING_FOR_REVIEW', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED'
));

CREATE TABLE robot_run_outputs (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_run_id uuid NOT NULL REFERENCES robot_runs(id) ON DELETE CASCADE,
    highlight_selection_id uuid NOT NULL REFERENCES highlight_selections(id) ON DELETE RESTRICT,
    highlight_selection_item_id uuid NOT NULL REFERENCES highlight_selection_items(id) ON DELETE RESTRICT,
    highlight_candidate_id uuid NOT NULL REFERENCES highlight_candidates(id) ON DELETE RESTRICT,
    selection_order integer NOT NULL CHECK (selection_order > 0),
    source_rank integer NOT NULL CHECK (source_rank > 0),
    status text NOT NULL CHECK (status IN (
        'CREATED', 'WAITING_FOR_DRAFT', 'WAITING_FOR_AI', 'WAITING_FOR_AI_REVIEW',
        'WAITING_FOR_REVIEW', 'SCHEDULED', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    content_draft_id uuid REFERENCES content_drafts(id) ON DELETE SET NULL,
    content_suggestion_id uuid REFERENCES content_suggestions(id) ON DELETE SET NULL,
    robot_approval_id uuid REFERENCES robot_approvals(id) ON DELETE SET NULL,
    publish_schedule_id uuid REFERENCES publish_schedules(id) ON DELETE SET NULL,
    failure_code varchar(100),
    failure_message varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT robot_run_outputs_item_unique UNIQUE (robot_run_id, highlight_selection_item_id),
    CONSTRAINT robot_run_outputs_order_unique UNIQUE (robot_run_id, selection_order),
    CONSTRAINT robot_run_outputs_draft_unique UNIQUE (content_draft_id),
    CONSTRAINT robot_run_outputs_schedule_unique UNIQUE (publish_schedule_id)
);
CREATE INDEX robot_run_outputs_run_order_idx ON robot_run_outputs(robot_run_id, selection_order);
CREATE INDEX robot_run_outputs_workspace_idx ON robot_run_outputs(workspace_id, created_at DESC);

ALTER TABLE content_drafts ADD COLUMN robot_run_output_id uuid REFERENCES robot_run_outputs(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX content_drafts_one_per_robot_run_output
    ON content_drafts(robot_run_output_id) WHERE robot_run_output_id IS NOT NULL;

ALTER TABLE content_suggestions ADD COLUMN robot_run_output_id uuid REFERENCES robot_run_outputs(id) ON DELETE SET NULL;
DROP INDEX content_suggestions_one_robot_suggestion_per_run;
CREATE UNIQUE INDEX content_suggestions_one_legacy_robot_suggestion_per_run
    ON content_suggestions(robot_run_id) WHERE origin = 'ROBOT' AND robot_run_output_id IS NULL;
CREATE UNIQUE INDEX content_suggestions_one_per_robot_run_output
    ON content_suggestions(robot_run_output_id) WHERE robot_run_output_id IS NOT NULL;

ALTER TABLE robot_approvals DROP CONSTRAINT robot_approvals_robot_run_id_key;
ALTER TABLE robot_approvals ADD COLUMN robot_run_output_id uuid REFERENCES robot_run_outputs(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX robot_approvals_one_legacy_per_run
    ON robot_approvals(robot_run_id) WHERE robot_run_output_id IS NULL;
CREATE UNIQUE INDEX robot_approvals_one_per_output
    ON robot_approvals(robot_run_output_id) WHERE robot_run_output_id IS NOT NULL;

ALTER TABLE publication_attributions ADD COLUMN robot_run_output_id uuid;
ALTER TABLE publication_attributions ADD COLUMN highlight_selection_id uuid;
ALTER TABLE publication_attributions ADD COLUMN highlight_selection_item_id uuid;
ALTER TABLE publication_attributions ADD COLUMN highlight_candidate_id uuid;
ALTER TABLE publication_attributions ADD COLUMN selection_order integer;
ALTER TABLE publication_attributions ADD COLUMN source_rank integer;
