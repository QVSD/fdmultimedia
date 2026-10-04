CREATE TABLE robot_adaptive_execution_authorizations (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    proposal_id uuid NOT NULL REFERENCES robot_change_proposals(id) ON DELETE RESTRICT,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    factor varchar(32) NOT NULL CHECK (factor = 'PERSONA'),
    from_persona_id uuid REFERENCES personas(id) ON DELETE RESTRICT,
    to_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    source_experiment_id uuid NOT NULL REFERENCES experiments(id) ON DELETE RESTRICT,
    max_executions integer NOT NULL CHECK (max_executions = 1),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED', 'INVALIDATED')),
    terminal_reason varchar(48) CHECK (terminal_reason IN (
        'AUTO_APPLIED', 'REVOKED_BY_HUMAN', 'EXPIRED', 'APPLIED_MANUALLY', 'PROPOSAL_STALE',
        'PROPOSAL_NOT_APPROVED', 'ROBOT_CONFIGURATION_CHANGED', 'TARGET_PERSONA_INACTIVE', 'SCOPE_MISMATCH'
    )),
    valid_from timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    policy_revision integer NOT NULL CHECK (policy_revision >= 0),
    guardrail_engine_version varchar(64) NOT NULL,
    execution_engine_version varchar(64) NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    last_evaluated_at timestamptz,
    terminated_at timestamptz,
    terminated_by_user_id uuid REFERENCES app_users(id),
    consumed_revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    consumed_guardrail_evaluation_id uuid REFERENCES adaptive_guardrail_evaluations(id) ON DELETE RESTRICT,
    CONSTRAINT robot_adaptive_execution_authorizations_window_ck CHECK (expires_at > valid_from),
    CONSTRAINT robot_adaptive_execution_authorizations_bounds_ck CHECK (
        expires_at >= valid_from + interval '1 hour' AND expires_at <= valid_from + interval '30 days'),
    CONSTRAINT robot_adaptive_execution_authorizations_lifecycle_ck CHECK (
        (status = 'ACTIVE' AND terminal_reason IS NULL AND terminated_at IS NULL AND consumed_revision_id IS NULL)
        OR (status = 'CONSUMED' AND terminal_reason = 'AUTO_APPLIED' AND terminated_at IS NOT NULL
            AND consumed_revision_id IS NOT NULL)
        OR (status IN ('REVOKED', 'EXPIRED', 'INVALIDATED') AND terminal_reason IS NOT NULL
            AND terminated_at IS NOT NULL AND consumed_revision_id IS NULL)
    )
);

CREATE UNIQUE INDEX robot_adaptive_execution_authorizations_one_active_idx
    ON robot_adaptive_execution_authorizations(proposal_id) WHERE status = 'ACTIVE';
CREATE INDEX robot_adaptive_execution_authorizations_reconcile_idx
    ON robot_adaptive_execution_authorizations(last_evaluated_at NULLS FIRST, created_at, id) WHERE status = 'ACTIVE';
CREATE INDEX robot_adaptive_execution_authorizations_expiry_idx
    ON robot_adaptive_execution_authorizations(expires_at) WHERE status = 'ACTIVE';
CREATE INDEX robot_adaptive_execution_authorizations_proposal_idx
    ON robot_adaptive_execution_authorizations(proposal_id, created_at DESC);
CREATE INDEX robot_adaptive_execution_authorizations_robot_idx
    ON robot_adaptive_execution_authorizations(robot_id, created_at DESC);
CREATE INDEX robot_adaptive_execution_authorizations_workspace_idx
    ON robot_adaptive_execution_authorizations(workspace_id, created_at DESC);

ALTER TABLE robot_configuration_revisions
    ADD COLUMN execution_origin varchar(32) NOT NULL DEFAULT 'HUMAN_APPLY'
        CHECK (execution_origin IN ('HUMAN_APPLY', 'PREAUTHORIZED_AUTO_APPLY', 'HUMAN_ROLLBACK')),
    ADD COLUMN execution_authorization_id uuid REFERENCES robot_adaptive_execution_authorizations(id) ON DELETE RESTRICT,
    ADD COLUMN execution_engine_version varchar(64);

UPDATE robot_configuration_revisions SET execution_origin = 'HUMAN_ROLLBACK' WHERE change_type = 'ROLLBACK';

ALTER TABLE robot_configuration_revisions
    ADD CONSTRAINT robot_configuration_revisions_execution_provenance_ck CHECK (
        execution_origin <> 'PREAUTHORIZED_AUTO_APPLY'
        OR (execution_authorization_id IS NOT NULL AND execution_engine_version IS NOT NULL
            AND change_type = 'PERSONA_CHANGE')),
    ADD CONSTRAINT robot_configuration_revisions_rollback_origin_ck CHECK (
        (execution_origin = 'HUMAN_ROLLBACK') = (change_type = 'ROLLBACK'));

CREATE UNIQUE INDEX robot_configuration_revisions_execution_authorization_unique
    ON robot_configuration_revisions(execution_authorization_id) WHERE execution_authorization_id IS NOT NULL;

CREATE TABLE adaptive_execution_attempts (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    authorization_id uuid NOT NULL REFERENCES robot_adaptive_execution_authorizations(id) ON DELETE RESTRICT,
    proposal_id uuid NOT NULL REFERENCES robot_change_proposals(id) ON DELETE RESTRICT,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    engine_version varchar(64) NOT NULL,
    trigger_type varchar(32) NOT NULL CHECK (trigger_type IN (
        'AUTHORIZATION_CREATED', 'RECONCILIATION', 'MANUAL_APPLY', 'AUTHORIZATION_REVOKED')),
    result varchar(24) NOT NULL CHECK (result IN (
        'APPLIED', 'BLOCKED', 'INVALIDATED', 'EXPIRED', 'REVOKED', 'ALREADY_APPLIED')),
    reason_codes varchar(500) NOT NULL,
    guardrail_evaluation_id uuid REFERENCES adaptive_guardrail_evaluations(id) ON DELETE RESTRICT,
    revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    state_fingerprint varchar(64) NOT NULL,
    attempted_at timestamptz NOT NULL,
    CONSTRAINT adaptive_execution_attempts_applied_revision_ck CHECK ((result = 'APPLIED') = (revision_id IS NOT NULL))
);

CREATE INDEX adaptive_execution_attempts_authorization_idx
    ON adaptive_execution_attempts(authorization_id, attempted_at DESC);
CREATE INDEX adaptive_execution_attempts_robot_idx
    ON adaptive_execution_attempts(robot_id, attempted_at DESC);
