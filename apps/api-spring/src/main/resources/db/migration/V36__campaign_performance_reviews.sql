CREATE TABLE campaign_performance_reviews (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_run_id uuid NOT NULL REFERENCES robot_runs(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision > 0),
    observation_window varchar(8) NOT NULL CHECK (observation_window IN ('H24', 'H72', 'D7')),
    primary_metric varchar(32) NOT NULL CHECK (primary_metric IN (
        'VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'
    )),
    engine_version varchar(64) NOT NULL,
    recommendation_engine_version varchar(64) NOT NULL,
    evidence_status varchar(32) NOT NULL CHECK (evidence_status IN (
        'TOO_YOUNG', 'METRIC_UNAVAILABLE', 'INSUFFICIENT_SAMPLE', 'LOW_COVERAGE', 'READY'
    )),
    robot_run_status_snapshot varchar(32) NOT NULL,
    intended_output_count integer NOT NULL CHECK (intended_output_count > 0),
    actual_output_count integer NOT NULL CHECK (actual_output_count >= 0),
    published_output_count integer NOT NULL CHECK (published_output_count >= 0),
    failed_output_count integer NOT NULL CHECK (failed_output_count >= 0),
    eligible_by_age_count integer NOT NULL CHECK (eligible_by_age_count >= 0),
    analytics_publication_count integer NOT NULL CHECK (analytics_publication_count >= 0),
    evidence_cutoff_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT campaign_performance_reviews_run_window_revision_unique
        UNIQUE (robot_run_id, observation_window, revision)
);

CREATE INDEX campaign_performance_reviews_workspace_created_idx
    ON campaign_performance_reviews(workspace_id, created_at DESC);
CREATE INDEX campaign_performance_reviews_run_window_idx
    ON campaign_performance_reviews(robot_run_id, observation_window, created_at DESC);

CREATE TABLE campaign_performance_review_outputs (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES campaign_performance_reviews(id) ON DELETE CASCADE,
    robot_run_output_id uuid REFERENCES robot_run_outputs(id) ON DELETE SET NULL,
    selection_order integer,
    source_rank integer,
    output_status varchar(32),
    campaign_role varchar(32),
    highlight_candidate_id uuid,
    campaign_plan_id uuid,
    campaign_plan_revision integer,
    campaign_plan_item_id uuid,
    campaign_copy_set_id uuid,
    campaign_copy_set_revision integer,
    campaign_copy_item_id uuid,
    content_suggestion_id uuid,
    content_draft_id uuid,
    publish_schedule_id uuid,
    publication_id uuid,
    provider varchar(32),
    published_at timestamptz,
    analytics_snapshot_id uuid,
    evidence_status varchar(32) NOT NULL CHECK (evidence_status IN (
        'UNPUBLISHED', 'TOO_YOUNG', 'MISSING_SNAPSHOT', 'OBSERVED'
    )),
    views bigint CHECK (views >= 0),
    reach bigint CHECK (reach >= 0),
    likes bigint CHECK (likes >= 0),
    comments bigint CHECK (comments >= 0),
    shares bigint CHECK (shares >= 0),
    saves bigint CHECK (saves >= 0),
    total_interactions bigint CHECK (total_interactions >= 0),
    created_at timestamptz NOT NULL,
    CONSTRAINT campaign_performance_review_outputs_order_valid
        CHECK (selection_order IS NULL OR selection_order > 0),
    CONSTRAINT campaign_performance_review_outputs_rank_valid
        CHECK (source_rank IS NULL OR source_rank > 0),
    CONSTRAINT campaign_performance_review_outputs_output_unique
        UNIQUE (review_id, robot_run_output_id)
);
CREATE INDEX campaign_performance_review_outputs_review_order_idx
    ON campaign_performance_review_outputs(review_id, selection_order, id);
CREATE UNIQUE INDEX campaign_performance_review_outputs_publication_unique
    ON campaign_performance_review_outputs(review_id, publication_id)
    WHERE publication_id IS NOT NULL;

CREATE TABLE campaign_performance_recommendations (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES campaign_performance_reviews(id) ON DELETE CASCADE,
    sequence integer NOT NULL CHECK (sequence > 0),
    recommendation_type varchar(64) NOT NULL CHECK (recommendation_type IN (
        'WAIT_FOR_OBSERVATION_WINDOW', 'COLLECT_MORE_DATA', 'CHECK_ANALYTICS_COVERAGE',
        'REVIEW_OUTPUT_DIFFERENCES', 'REVIEW_ROLE_DIFFERENCES', 'REVIEW_COPY_DIFFERENCES',
        'CONSIDER_CONTROLLED_EXPERIMENT'
    )),
    metric varchar(32),
    compared_dimension varchar(32),
    evidence jsonb NOT NULL,
    message varchar(500) NOT NULL,
    limitations jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT campaign_performance_recommendations_sequence_unique UNIQUE (review_id, sequence)
);
CREATE INDEX campaign_performance_recommendations_review_idx
    ON campaign_performance_recommendations(review_id, sequence);
