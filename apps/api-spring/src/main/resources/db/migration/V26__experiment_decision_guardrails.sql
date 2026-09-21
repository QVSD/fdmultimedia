ALTER TABLE experiments ADD COLUMN minimum_practical_effect NUMERIC(20,4);
ALTER TABLE experiments ADD CONSTRAINT experiments_practical_effect_positive
    CHECK (minimum_practical_effect IS NULL OR minimum_practical_effect > 0);

CREATE TABLE experiment_decisions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    experiment_id UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    decision TEXT NOT NULL,
    selected_variant_key TEXT,
    rationale TEXT NOT NULL,
    decided_by_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    decided_at TIMESTAMPTZ NOT NULL,
    guardrail_version TEXT NOT NULL,
    analysis_version TEXT NOT NULL,
    experiment_status_snapshot TEXT NOT NULL,
    primary_metric_snapshot TEXT NOT NULL,
    observation_window_snapshot TEXT NOT NULL,
    minimum_practical_effect_snapshot NUMERIC(20,4),
    analysis_population_snapshot TEXT NOT NULL,
    variant_a_sample_size_snapshot BIGINT NOT NULL,
    variant_b_sample_size_snapshot BIGINT NOT NULL,
    absolute_mean_difference_snapshot NUMERIC(24,4),
    confidence_interval_lower_snapshot NUMERIC(24,4),
    confidence_interval_upper_snapshot NUMERIC(24,4),
    p_value_snapshot NUMERIC(12,6),
    standardized_effect_size_snapshot NUMERIC(24,4),
    readiness_status_snapshot TEXT NOT NULL,
    evidence_fingerprint CHAR(64) NOT NULL,
    CONSTRAINT experiment_decisions_key_unique UNIQUE (workspace_id, experiment_id, idempotency_key),
    CONSTRAINT experiment_decisions_type_valid CHECK (decision IN
        ('SELECT_VARIANT_A','SELECT_VARIANT_B','KEEP_CURRENT_CONFIGURATION','INCONCLUSIVE','CANCEL_EXPERIMENT')),
    CONSTRAINT experiment_decisions_variant_valid CHECK (
        (decision = 'SELECT_VARIANT_A' AND selected_variant_key = 'A') OR
        (decision = 'SELECT_VARIANT_B' AND selected_variant_key = 'B') OR
        (decision NOT IN ('SELECT_VARIANT_A','SELECT_VARIANT_B') AND selected_variant_key IS NULL)),
    CONSTRAINT experiment_decisions_rationale_length CHECK (char_length(rationale) BETWEEN 1 AND 2000),
    CONSTRAINT experiment_decisions_population_valid CHECK
        (analysis_population_snapshot IN ('ASSIGNED_OBSERVED','PER_PROTOCOL_OBSERVED')),
    CONSTRAINT experiment_decisions_readiness_valid CHECK
        (readiness_status_snapshot IN ('NOT_READY','READY_FOR_REVIEW'))
);
CREATE INDEX experiment_decisions_history_idx ON experiment_decisions (experiment_id, decided_at DESC, id DESC);
