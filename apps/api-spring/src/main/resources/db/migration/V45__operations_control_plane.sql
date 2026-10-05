-- Phase 17Q: operations control plane. Operational state only; no business-domain table is touched.

-- One logical scheduler is observed by every replica; the logical status is the latest result across instances.
CREATE TABLE operations_scheduler_status (
    scheduler_name varchar(64) NOT NULL,
    instance_id varchar(128) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    last_started_at timestamptz,
    last_completed_at timestamptz,
    last_succeeded_at timestamptz,
    last_failed_at timestamptz,
    last_duration_ms bigint CHECK (last_duration_ms IS NULL OR last_duration_ms >= 0),
    processed_count bigint NOT NULL DEFAULT 0 CHECK (processed_count >= 0),
    result_count bigint NOT NULL DEFAULT 0 CHECK (result_count >= 0),
    last_failure_code varchar(120),
    updated_at timestamptz NOT NULL,
    CONSTRAINT operations_scheduler_status_pk PRIMARY KEY (scheduler_name, instance_id)
);

CREATE INDEX operations_scheduler_status_updated_idx ON operations_scheduler_status (updated_at);

-- Deterministic operational incidents. Rows are created when a derived condition is first observed, updated at most once per
-- reconciliation tick, resolved automatically when the condition disappears, and kept as bounded history.
CREATE TABLE operations_incidents (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    incident_key varchar(160) NOT NULL,
    engine_version varchar(40) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'RESOLVED')),
    category varchar(32) NOT NULL,
    title varchar(200) NOT NULL,
    condition_code varchar(64) NOT NULL,
    detail varchar(500),
    suggested_action varchar(300) NOT NULL,
    subject_type varchar(32),
    subject_id uuid,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    resolved_at timestamptz,
    acknowledged_at timestamptz,
    acknowledged_by_user_id uuid REFERENCES app_users(id),
    CONSTRAINT operations_incidents_lifecycle_ck CHECK (
        (status = 'ACTIVE' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_at >= first_observed_at)),
    CONSTRAINT operations_incidents_ack_ck CHECK (
        (acknowledged_at IS NULL AND acknowledged_by_user_id IS NULL)
        OR (acknowledged_at IS NOT NULL AND acknowledged_by_user_id IS NOT NULL))
);

-- One active incident per workspace and deterministic key: the same ongoing condition appears once.
CREATE UNIQUE INDEX operations_incidents_one_active_idx ON operations_incidents (workspace_id, incident_key) WHERE status = 'ACTIVE';
CREATE INDEX operations_incidents_workspace_status_idx ON operations_incidents (workspace_id, status, severity, first_observed_at);
CREATE INDEX operations_incidents_resolved_idx ON operations_incidents (resolved_at) WHERE status = 'RESOLVED';

-- Supporting indexes for the bounded, workspace-scoped reads of the control plane (recent job outcomes, guardrail blocks).
CREATE INDEX jobs_workspace_finished_idx ON jobs (workspace_id, finished_at DESC) WHERE finished_at IS NOT NULL;
CREATE INDEX adaptive_guardrail_evaluations_workspace_idx ON adaptive_guardrail_evaluations (workspace_id, evaluated_at DESC);
