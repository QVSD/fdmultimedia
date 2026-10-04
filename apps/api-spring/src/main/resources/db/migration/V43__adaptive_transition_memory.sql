-- Phase 17N: deterministic, rebuildable adaptive transition memory (ADAPTIVE_MEMORY_V1).
-- Schema only: the projection is materialized lazily/by reconciliation from existing immutable history, never by this migration.

CREATE TABLE robot_adaptive_transition_memory_events (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    from_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    to_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL CHECK (event_type IN (
        'PROPOSAL_CREATED', 'PROPOSAL_REJECTED', 'CHANGE_PROPOSAL_CREATED', 'CHANGE_PROPOSAL_APPROVED', 'CHANGE_APPLIED',
        'SAFETY_STABLE', 'SAFETY_REGRESSION', 'ROLLBACK_RECOMMENDED', 'ROLLBACK_DISMISSED', 'CHANGE_ROLLED_BACK',
        'TRANSITION_SUPERSEDED')),
    source_type varchar(40) NOT NULL CHECK (source_type IN (
        'OPTIMIZATION_PROPOSAL', 'ROBOT_CHANGE_PROPOSAL', 'ROBOT_CONFIGURATION_REVISION', 'POST_CHANGE_SAFETY_EVALUATION',
        'ROLLBACK_RECOMMENDATION')),
    source_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    engine_version varchar(40) NOT NULL,
    revision_id uuid,
    experiment_id uuid,
    evaluation_id uuid,
    recommendation_id uuid,
    execution_origin varchar(32),
    observation_window varchar(8),
    detail varchar(64),
    projected_at timestamptz NOT NULL,
    CONSTRAINT robot_adaptive_transition_memory_events_distinct_ck CHECK (from_persona_id <> to_persona_id),
    CONSTRAINT robot_adaptive_transition_memory_events_source_unique UNIQUE (source_type, source_id, event_type)
);

CREATE INDEX robot_adaptive_transition_memory_events_robot_idx
    ON robot_adaptive_transition_memory_events (robot_id, occurred_at, source_type, source_id);
CREATE INDEX robot_adaptive_transition_memory_events_transition_idx
    ON robot_adaptive_transition_memory_events (robot_id, from_persona_id, to_persona_id, occurred_at);
CREATE INDEX robot_adaptive_transition_memory_events_workspace_idx
    ON robot_adaptive_transition_memory_events (workspace_id, projected_at DESC);

-- Directional (Robot, from, to) projection row. Every column is derivable from the events above.
CREATE TABLE robot_adaptive_transition_memory (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    from_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    to_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    factor varchar(16) NOT NULL CHECK (factor = 'PERSONA'),
    engine_version varchar(40) NOT NULL,
    latest_outcome varchar(32) NOT NULL CHECK (latest_outcome IN (
        'PROPOSED', 'HUMAN_REJECTED', 'APPROVED_NOT_APPLIED', 'APPLIED', 'OBSERVED_STABLE', 'OBSERVED_REGRESSION',
        'ROLLED_BACK', 'SUPERSEDED')),
    latest_safety_status varchar(32) CHECK (latest_safety_status IN ('READY_STABLE', 'READY_REGRESSION_OBSERVED')),
    proposal_count integer NOT NULL CHECK (proposal_count >= 0),
    apply_count integer NOT NULL CHECK (apply_count >= 0),
    rollback_count integer NOT NULL CHECK (rollback_count >= 0),
    regression_count integer NOT NULL CHECK (regression_count >= 0),
    event_count integer NOT NULL CHECK (event_count >= 1),
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    last_proposed_at timestamptz,
    last_rejected_at timestamptz,
    last_applied_at timestamptz,
    last_regression_at timestamptz,
    last_rolled_back_at timestamptz,
    latest_evidence_at timestamptz,
    latest_revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    latest_evaluation_id uuid REFERENCES post_change_safety_evaluations(id) ON DELETE RESTRICT,
    latest_recommendation_id uuid REFERENCES rollback_recommendations(id) ON DELETE RESTRICT,
    latest_experiment_id uuid REFERENCES experiments(id) ON DELETE RESTRICT,
    last_projected_at timestamptz NOT NULL,
    CONSTRAINT robot_adaptive_transition_memory_distinct_ck CHECK (from_persona_id <> to_persona_id),
    CONSTRAINT robot_adaptive_transition_memory_key_unique UNIQUE (robot_id, from_persona_id, to_persona_id),
    CONSTRAINT robot_adaptive_transition_memory_seen_ck CHECK (last_seen_at >= first_seen_at)
);

CREATE INDEX robot_adaptive_transition_memory_workspace_idx ON robot_adaptive_transition_memory (workspace_id, last_seen_at DESC);
CREATE INDEX robot_adaptive_transition_memory_robot_idx ON robot_adaptive_transition_memory (robot_id, last_seen_at DESC);
CREATE INDEX robot_adaptive_transition_memory_suppression_idx
    ON robot_adaptive_transition_memory (robot_id, last_rolled_back_at, last_regression_at, last_rejected_at);

-- Per-Robot reconciliation watermark so the bounded reconciler serves Robots fairly.
CREATE TABLE robot_adaptive_memory_state (
    robot_id uuid PRIMARY KEY REFERENCES robots(id) ON DELETE CASCADE,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    last_reconciled_at timestamptz NOT NULL,
    event_count integer NOT NULL CHECK (event_count >= 0)
);

CREATE INDEX robot_adaptive_memory_state_reconcile_idx ON robot_adaptive_memory_state (last_reconciled_at);
