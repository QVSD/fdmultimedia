CREATE TABLE robot_change_proposals (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_optimization_proposal_id uuid NOT NULL REFERENCES optimization_proposals(id) ON DELETE RESTRICT,
    source_experiment_id uuid NOT NULL REFERENCES experiments(id) ON DELETE RESTRICT,
    engine_version varchar(64) NOT NULL,
    factor varchar(32) NOT NULL CHECK (factor = 'PERSONA'),
    status varchar(32) NOT NULL CHECK (status IN (
        'READY_FOR_REVIEW', 'APPROVED', 'APPLIED', 'REJECTED', 'STALE', 'ROLLED_BACK', 'FAILED'
    )),
    target_robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    target_robot_name_snapshot varchar(200) NOT NULL,
    current_persona_id uuid REFERENCES personas(id) ON DELETE RESTRICT,
    current_persona_name_snapshot varchar(200),
    current_persona_fingerprint varchar(64) NOT NULL,
    proposed_persona_id uuid NOT NULL REFERENCES personas(id) ON DELETE RESTRICT,
    proposed_persona_name_snapshot varchar(200) NOT NULL,
    proposed_persona_fingerprint varchar(64) NOT NULL,
    expected_robot_config_fingerprint varchar(64) NOT NULL,
    analysis_engine_version varchar(64) NOT NULL,
    metric varchar(32) NOT NULL CHECK (metric IN (
        'VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'
    )),
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H24', 'H72', 'D7')),
    population varchar(32) NOT NULL CHECK (population = 'ASSIGNED_OBSERVED'),
    baseline_sample_count integer NOT NULL CHECK (baseline_sample_count >= 0),
    candidate_sample_count integer NOT NULL CHECK (candidate_sample_count >= 0),
    baseline_coverage numeric(7,6),
    candidate_coverage numeric(7,6),
    absolute_mean_difference numeric(30,6) NOT NULL,
    relative_mean_difference_percent numeric(20,6),
    standard_error numeric(30,6),
    degrees_of_freedom numeric(20,6),
    confidence_interval_lower numeric(30,6),
    confidence_interval_upper numeric(30,6),
    confidence_interval_includes_zero boolean,
    p_value numeric(20,6),
    standardized_effect_size numeric(20,6),
    limitations varchar(2000) NOT NULL,
    rationale varchar(1000) NOT NULL,
    proposal_fingerprint varchar(64) NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    applied_at timestamptz,
    rolled_back_at timestamptz,
    CONSTRAINT robot_change_proposals_personas_distinct CHECK (current_persona_id IS NULL OR current_persona_id <> proposed_persona_id)
);

CREATE INDEX robot_change_proposals_workspace_created_idx
    ON robot_change_proposals(workspace_id, created_at DESC);
CREATE INDEX robot_change_proposals_target_robot_idx
    ON robot_change_proposals(target_robot_id, created_at DESC);
CREATE INDEX robot_change_proposals_source_idx
    ON robot_change_proposals(source_optimization_proposal_id);

CREATE TABLE robot_configuration_revisions (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_id uuid NOT NULL REFERENCES robots(id) ON DELETE RESTRICT,
    revision integer NOT NULL CHECK (revision > 0),
    change_type varchar(32) NOT NULL CHECK (change_type IN ('PERSONA_CHANGE', 'ROLLBACK')),
    previous_persona_id uuid REFERENCES personas(id) ON DELETE RESTRICT,
    previous_persona_name_snapshot varchar(200),
    new_persona_id uuid REFERENCES personas(id) ON DELETE RESTRICT,
    new_persona_name_snapshot varchar(200),
    previous_config_fingerprint varchar(64) NOT NULL,
    new_config_fingerprint varchar(64) NOT NULL,
    source_proposal_id uuid REFERENCES robot_change_proposals(id) ON DELETE RESTRICT,
    source_experiment_id uuid REFERENCES experiments(id) ON DELETE RESTRICT,
    rollback_of_revision_id uuid REFERENCES robot_configuration_revisions(id) ON DELETE RESTRICT,
    actor_user_id uuid NOT NULL REFERENCES app_users(id),
    reason varchar(500),
    created_at timestamptz NOT NULL,
    CONSTRAINT robot_configuration_revisions_robot_revision_unique UNIQUE (robot_id, revision)
);

CREATE INDEX robot_configuration_revisions_robot_idx
    ON robot_configuration_revisions(robot_id, revision DESC);
CREATE INDEX robot_configuration_revisions_workspace_created_idx
    ON robot_configuration_revisions(workspace_id, created_at DESC);
CREATE INDEX robot_configuration_revisions_source_proposal_idx
    ON robot_configuration_revisions(source_proposal_id);
