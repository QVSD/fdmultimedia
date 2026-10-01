CREATE TABLE optimization_proposals (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_review_id uuid NOT NULL REFERENCES campaign_performance_reviews(id) ON DELETE RESTRICT,
    revision integer NOT NULL CHECK (revision > 0),
    is_current boolean NOT NULL DEFAULT true,
    engine_version varchar(64) NOT NULL,
    factor varchar(32) NOT NULL CHECK (factor = 'PERSONA'),
    status varchar(32) NOT NULL CHECK (status IN (
        'READY_FOR_REVIEW', 'APPROVED', 'REJECTED', 'MATERIALIZED', 'STALE', 'FAILED'
    )),
    baseline_persona_id uuid NOT NULL,
    baseline_persona_name_snapshot varchar(200) NOT NULL,
    baseline_persona_fingerprint varchar(64) NOT NULL,
    candidate_persona_id uuid NOT NULL,
    candidate_persona_name_snapshot varchar(200) NOT NULL,
    candidate_persona_fingerprint varchar(64) NOT NULL,
    metric varchar(32) NOT NULL CHECK (metric IN (
        'VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'
    )),
    statistic varchar(16) NOT NULL CHECK (statistic = 'MEDIAN'),
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H24', 'H72', 'D7')),
    provider varchar(32) NOT NULL,
    cohort_from timestamptz NOT NULL,
    cohort_to timestamptz NOT NULL,
    baseline_sample integer NOT NULL CHECK (baseline_sample >= 0),
    candidate_sample integer NOT NULL CHECK (candidate_sample >= 0),
    baseline_eligible integer NOT NULL CHECK (baseline_eligible >= 0),
    candidate_eligible integer NOT NULL CHECK (candidate_eligible >= 0),
    baseline_coverage numeric(7,6) NOT NULL CHECK (baseline_coverage BETWEEN 0 AND 1),
    candidate_coverage numeric(7,6) NOT NULL CHECK (candidate_coverage BETWEEN 0 AND 1),
    baseline_value numeric(30,6) NOT NULL,
    candidate_value numeric(30,6) NOT NULL,
    absolute_difference numeric(30,6) NOT NULL,
    relative_difference_percent numeric(20,6),
    direction varchar(32) NOT NULL CHECK (direction IN ('HIGHER_OBSERVED', 'LOWER_OBSERVED', 'SIMILAR_OBSERVED')),
    evidence_fingerprint varchar(64) NOT NULL,
    rationale varchar(1000) NOT NULL,
    limitation varchar(1000) NOT NULL,
    materialized_experiment_id uuid,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    materialized_at timestamptz,
    CONSTRAINT optimization_proposals_personas_distinct CHECK (baseline_persona_id <> candidate_persona_id),
    CONSTRAINT optimization_proposals_cohort_range CHECK (cohort_from < cohort_to),
    CONSTRAINT optimization_proposals_context_revision_unique UNIQUE (
        source_review_id, baseline_persona_id, candidate_persona_id, metric, statistic, revision
    ),
    CONSTRAINT optimization_proposals_materialized_experiment_unique UNIQUE (materialized_experiment_id)
);

CREATE UNIQUE INDEX optimization_proposals_current_context_idx
    ON optimization_proposals(source_review_id, baseline_persona_id, candidate_persona_id, metric, statistic)
    WHERE is_current;
CREATE INDEX optimization_proposals_workspace_created_idx
    ON optimization_proposals(workspace_id, created_at DESC);
CREATE INDEX optimization_proposals_review_idx
    ON optimization_proposals(source_review_id, revision DESC);

ALTER TABLE experiments ADD COLUMN optimization_proposal_id uuid
    REFERENCES optimization_proposals(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX experiments_optimization_proposal_unique
    ON experiments(optimization_proposal_id) WHERE optimization_proposal_id IS NOT NULL;

ALTER TABLE optimization_proposals ADD CONSTRAINT optimization_proposals_materialized_experiment_fk
    FOREIGN KEY (materialized_experiment_id) REFERENCES experiments(id) ON DELETE SET NULL;
