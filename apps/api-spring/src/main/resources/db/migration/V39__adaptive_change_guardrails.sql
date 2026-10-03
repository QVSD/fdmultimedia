CREATE TABLE robot_adaptive_policies (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision > 0),
    enabled boolean NOT NULL,
    max_applied_changes_per_window integer NOT NULL CHECK (max_applied_changes_per_window BETWEEN 1 AND 10),
    change_budget_window_days integer NOT NULL CHECK (change_budget_window_days BETWEEN 1 AND 365),
    cooldown_hours integer NOT NULL CHECK (cooldown_hours BETWEEN 0 AND 2160),
    require_no_active_experiment boolean NOT NULL,
    require_no_pending_change boolean NOT NULL,
    require_post_change_observation boolean NOT NULL,
    updated_by_user_id uuid NOT NULL REFERENCES app_users(id),
    updated_at timestamptz NOT NULL,
    CONSTRAINT robot_adaptive_policies_robot_unique UNIQUE (robot_id)
);

CREATE TABLE robot_adaptive_policy_revisions (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision > 0),
    previous_values varchar(500),
    new_values varchar(500) NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT robot_adaptive_policy_revisions_unique UNIQUE (robot_id, revision)
);

CREATE TABLE adaptive_guardrail_evaluations (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    proposal_id uuid NOT NULL REFERENCES robot_change_proposals(id) ON DELETE RESTRICT,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    engine_version varchar(64) NOT NULL,
    trigger_type varchar(16) NOT NULL CHECK (trigger_type IN ('CREATE', 'APPROVE', 'APPLY', 'CHECK')),
    policy_revision integer NOT NULL CHECK (policy_revision >= 0),
    eligible boolean NOT NULL,
    reason_codes varchar(1000) NOT NULL,
    budget_allowed integer NOT NULL CHECK (budget_allowed BETWEEN 1 AND 10),
    budget_used integer NOT NULL CHECK (budget_used >= 0),
    budget_window_days integer NOT NULL CHECK (budget_window_days BETWEEN 1 AND 365),
    latest_configuration_revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    last_configuration_change_at timestamptz,
    cooldown_hours integer NOT NULL CHECK (cooldown_hours BETWEEN 0 AND 2160),
    cooldown_ends_at timestamptz,
    active_experiment_id uuid REFERENCES experiments(id) ON DELETE SET NULL,
    pending_proposal_count integer NOT NULL CHECK (pending_proposal_count >= 0),
    runs_since_revision integer NOT NULL CHECK (runs_since_revision >= 0),
    publications_since_revision integer NOT NULL CHECK (publications_since_revision >= 0),
    eligible_by_age_count integer NOT NULL CHECK (eligible_by_age_count >= 0),
    analytics_publication_count integer NOT NULL CHECK (analytics_publication_count >= 0),
    metric_sample_count integer NOT NULL CHECK (metric_sample_count >= 0),
    coverage numeric(7,6),
    evaluated_at timestamptz NOT NULL
);

ALTER TABLE robot_configuration_revisions
    ADD COLUMN guardrail_evaluation_id uuid REFERENCES adaptive_guardrail_evaluations(id) ON DELETE RESTRICT,
    ADD COLUMN adaptive_policy_revision integer,
    ADD COLUMN guardrail_engine_version varchar(64);

CREATE INDEX robot_adaptive_policies_workspace_idx ON robot_adaptive_policies(workspace_id, robot_id);
CREATE INDEX robot_adaptive_policy_revisions_history_idx ON robot_adaptive_policy_revisions(robot_id, revision DESC);
CREATE INDEX adaptive_guardrail_evaluations_proposal_idx ON adaptive_guardrail_evaluations(proposal_id, evaluated_at DESC);
CREATE INDEX adaptive_guardrail_evaluations_robot_idx ON adaptive_guardrail_evaluations(robot_id, evaluated_at DESC);
CREATE INDEX robot_configuration_revisions_forward_budget_idx
    ON robot_configuration_revisions(robot_id, created_at DESC) WHERE change_type = 'PERSONA_CHANGE';
CREATE INDEX robot_runs_robot_created_guardrail_idx ON robot_runs(robot_id, created_at DESC);
