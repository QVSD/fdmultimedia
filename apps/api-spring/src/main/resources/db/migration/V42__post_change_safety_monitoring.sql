-- Phase 17M: post-change safety monitoring and human-governed rollback recommendations.
-- Observational only: nothing here mutates a Robot, a policy or an authorization.

CREATE TABLE post_change_safety_monitors (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    robot_revision integer NOT NULL CHECK (robot_revision > 0),
    factor varchar(32) NOT NULL CHECK (factor = 'PERSONA'),
    execution_origin varchar(32) NOT NULL CHECK (execution_origin IN ('HUMAN_APPLY', 'PREAUTHORIZED_AUTO_APPLY')),
    execution_authorization_id uuid REFERENCES robot_adaptive_execution_authorizations(id) ON DELETE RESTRICT,
    source_proposal_id uuid REFERENCES robot_change_proposals(id) ON DELETE RESTRICT,
    source_experiment_id uuid REFERENCES experiments(id) ON DELETE RESTRICT,
    previous_persona_id uuid REFERENCES personas(id) ON DELETE RESTRICT,
    previous_persona_name varchar(255),
    new_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    new_persona_name varchar(255) NOT NULL,
    metric varchar(32) CHECK (metric IN ('VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS')),
    epoch_start timestamptz NOT NULL,
    epoch_end timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('MONITORING', 'COMPLETED', 'SUPERSEDED')),
    completed_reason varchar(32) CHECK (completed_reason IN ('HORIZON_REACHED', 'EPOCH_SUPERSEDED')),
    engine_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_evaluated_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT post_change_safety_monitors_revision_unique UNIQUE (revision_id),
    CONSTRAINT post_change_safety_monitors_epoch_ck CHECK (epoch_end IS NULL OR epoch_end >= epoch_start),
    CONSTRAINT post_change_safety_monitors_lifecycle_ck CHECK (
        (status = 'MONITORING' AND completed_reason IS NULL)
        OR (status = 'COMPLETED' AND completed_reason = 'HORIZON_REACHED')
        OR (status = 'SUPERSEDED' AND completed_reason = 'EPOCH_SUPERSEDED' AND epoch_end IS NOT NULL)
    )
);

CREATE INDEX post_change_safety_monitors_reconcile_idx
    ON post_change_safety_monitors (last_evaluated_at NULLS FIRST, created_at) WHERE status = 'MONITORING';
CREATE INDEX post_change_safety_monitors_robot_idx ON post_change_safety_monitors (robot_id, robot_revision DESC);
CREATE INDEX post_change_safety_monitors_workspace_idx ON post_change_safety_monitors (workspace_id, created_at DESC);

-- Frozen, immutable per-window pre-change baseline derived from the controlled Experiment that justified the change.
CREATE TABLE post_change_safety_baselines (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    monitor_id uuid NOT NULL REFERENCES post_change_safety_monitors(id) ON DELETE CASCADE,
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H24', 'H72', 'D7')),
    source_experiment_id uuid NOT NULL REFERENCES experiments(id) ON DELETE RESTRICT,
    analysis_engine_version varchar(64) NOT NULL,
    metric varchar(32) NOT NULL,
    provider varchar(32) NOT NULL,
    assignment_count integer NOT NULL CHECK (assignment_count >= 0),
    eligible_count integer NOT NULL CHECK (eligible_count >= 0),
    sample_count integer NOT NULL CHECK (sample_count >= 0),
    eligible_coverage numeric(7, 6) NOT NULL CHECK (eligible_coverage >= 0 AND eligible_coverage <= 1),
    assignment_coverage numeric(7, 6) NOT NULL CHECK (assignment_coverage >= 0 AND assignment_coverage <= 1),
    baseline_mean numeric(24, 4) NOT NULL,
    baseline_fingerprint varchar(64) NOT NULL,
    frozen_at timestamptz NOT NULL,
    CONSTRAINT post_change_safety_baselines_monitor_window_unique UNIQUE (monitor_id, observation_window)
);

CREATE TABLE post_change_safety_evaluations (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    monitor_id uuid NOT NULL REFERENCES post_change_safety_monitors(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    evaluation_revision integer NOT NULL CHECK (evaluation_revision > 0),
    engine_version varchar(64) NOT NULL,
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H24', 'H72', 'D7')),
    informational boolean NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'SUPERSEDED', 'BASELINE_UNAVAILABLE', 'TOO_YOUNG', 'METRIC_UNAVAILABLE', 'INSUFFICIENT_SAMPLE',
        'LOW_COVERAGE', 'NOT_COMPARABLE', 'READY_STABLE', 'READY_REGRESSION_OBSERVED')),
    reason_codes varchar(255) NOT NULL,
    trigger_type varchar(16) NOT NULL CHECK (trigger_type IN ('EXPLICIT', 'RECONCILIATION')),
    metric varchar(32),
    provider varchar(32),
    adverse_direction varchar(24) CHECK (adverse_direction IN ('LOWER_IS_ADVERSE', 'HIGHER_IS_ADVERSE')),
    execution_origin varchar(32) NOT NULL,
    execution_authorization_id uuid,
    source_proposal_id uuid,
    source_experiment_id uuid,
    previous_persona_id uuid,
    new_persona_id uuid NOT NULL,
    baseline_id uuid REFERENCES post_change_safety_baselines(id) ON DELETE RESTRICT,
    baseline_sample integer,
    baseline_coverage numeric(7, 6),
    baseline_value numeric(24, 4),
    epoch_start timestamptz NOT NULL,
    epoch_end timestamptz,
    cohort_end timestamptz NOT NULL,
    post_runs integer NOT NULL CHECK (post_runs >= 0),
    post_published integer NOT NULL CHECK (post_published >= 0),
    post_eligible integer NOT NULL CHECK (post_eligible >= 0),
    post_sample integer NOT NULL CHECK (post_sample >= 0),
    post_coverage numeric(7, 6),
    post_value numeric(24, 4),
    absolute_difference numeric(24, 4),
    relative_difference_percent numeric(14, 4),
    material_threshold_percent numeric(8, 4) NOT NULL,
    min_sample integer NOT NULL CHECK (min_sample >= 1),
    min_coverage numeric(7, 6) NOT NULL,
    evidence_fingerprint varchar(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    CONSTRAINT post_change_safety_evaluations_revision_unique UNIQUE (monitor_id, observation_window, evaluation_revision),
    CONSTRAINT post_change_safety_evaluations_fingerprint_unique UNIQUE (monitor_id, observation_window, evidence_fingerprint),
    CONSTRAINT post_change_safety_evaluations_ready_ck CHECK (
        status NOT IN ('READY_STABLE', 'READY_REGRESSION_OBSERVED')
        OR (baseline_value IS NOT NULL AND post_value IS NOT NULL AND absolute_difference IS NOT NULL
            AND adverse_direction IS NOT NULL AND metric IS NOT NULL AND provider IS NOT NULL))
);

CREATE INDEX post_change_safety_evaluations_history_idx
    ON post_change_safety_evaluations (robot_id, revision_id, evaluated_at DESC);
CREATE INDEX post_change_safety_evaluations_latest_idx
    ON post_change_safety_evaluations (monitor_id, observation_window, evaluation_revision DESC);
CREATE INDEX post_change_safety_evaluations_workspace_idx ON post_change_safety_evaluations (workspace_id, evaluated_at DESC);

CREATE TABLE rollback_recommendations (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    robot_revision integer NOT NULL CHECK (robot_revision > 0),
    monitor_id uuid NOT NULL REFERENCES post_change_safety_monitors(id) ON DELETE CASCADE,
    evaluation_id uuid NOT NULL REFERENCES post_change_safety_evaluations(id) ON DELETE RESTRICT,
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H72', 'D7')),
    status varchar(16) NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'DISMISSED', 'ROLLED_BACK', 'SUPERSEDED')),
    metric varchar(32) NOT NULL,
    provider varchar(32) NOT NULL,
    previous_persona_id uuid,
    previous_persona_name varchar(255),
    current_persona_id uuid NOT NULL,
    current_persona_name varchar(255) NOT NULL,
    execution_origin varchar(32) NOT NULL,
    execution_authorization_id uuid,
    baseline_sample integer NOT NULL,
    baseline_value numeric(24, 4) NOT NULL,
    post_sample integer NOT NULL,
    post_coverage numeric(7, 6) NOT NULL,
    post_value numeric(24, 4) NOT NULL,
    absolute_difference numeric(24, 4) NOT NULL,
    relative_difference_percent numeric(14, 4) NOT NULL,
    reason varchar(500) NOT NULL,
    limitations varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL,
    acknowledged_at timestamptz,
    acknowledged_by_user_id uuid REFERENCES app_users(id),
    dismissed_at timestamptz,
    dismissed_by_user_id uuid REFERENCES app_users(id),
    resolved_at timestamptz,
    rollback_revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT rollback_recommendations_evaluation_unique UNIQUE (evaluation_id),
    CONSTRAINT rollback_recommendations_window_unique UNIQUE (revision_id, observation_window),
    CONSTRAINT rollback_recommendations_lifecycle_ck CHECK (
        (status IN ('OPEN', 'ACKNOWLEDGED') AND resolved_at IS NULL AND rollback_revision_id IS NULL)
        OR (status = 'DISMISSED' AND dismissed_at IS NOT NULL AND resolved_at IS NOT NULL AND rollback_revision_id IS NULL)
        OR (status = 'ROLLED_BACK' AND resolved_at IS NOT NULL AND rollback_revision_id IS NOT NULL)
        OR (status = 'SUPERSEDED' AND resolved_at IS NOT NULL AND rollback_revision_id IS NULL)),
    CONSTRAINT rollback_recommendations_acknowledged_ck CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL)
);

-- At most one actionable (OPEN or ACKNOWLEDGED) recommendation per configuration revision.
CREATE UNIQUE INDEX rollback_recommendations_one_actionable_idx
    ON rollback_recommendations (revision_id) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX rollback_recommendations_workspace_status_idx ON rollback_recommendations (workspace_id, status, created_at DESC);
CREATE INDEX rollback_recommendations_robot_idx ON rollback_recommendations (robot_id, created_at DESC);
