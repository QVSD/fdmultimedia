ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA', 'CREATE_CLIP', 'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS', 'TRANSCRIBE_MEDIA', 'PUBLISH_MEDIA', 'GENERATE_SOCIAL_COPY', 'GENERATE_CAMPAIGN_PLAN',
    'GENERATE_COORDINATED_SOCIAL_COPY'
));

ALTER TABLE robots ADD COLUMN copy_coordination_policy varchar(32) NOT NULL DEFAULT 'INDEPENDENT_COPY';
ALTER TABLE robots ADD CONSTRAINT robots_copy_coordination_policy_valid CHECK (copy_coordination_policy IN (
    'INDEPENDENT_COPY', 'COORDINATED_COPY_FOR_REVIEW', 'COORDINATED_COPY_AND_APPLY'
));

ALTER TABLE robot_runs ADD COLUMN copy_coordination_policy_snapshot varchar(32) NOT NULL DEFAULT 'INDEPENDENT_COPY';
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_copy_coordination_policy_snapshot_valid CHECK (copy_coordination_policy_snapshot IN (
    'INDEPENDENT_COPY', 'COORDINATED_COPY_FOR_REVIEW', 'COORDINATED_COPY_AND_APPLY'
));
ALTER TABLE robot_runs ADD COLUMN campaign_copy_set_id uuid;

CREATE TABLE campaign_copy_sets (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_run_id uuid NOT NULL REFERENCES robot_runs(id) ON DELETE CASCADE,
    campaign_plan_id uuid NOT NULL REFERENCES campaign_content_plans(id),
    revision integer NOT NULL,
    is_current boolean NOT NULL DEFAULT true,
    status varchar(32) NOT NULL,
    generator_version varchar(64) NOT NULL,
    provider varchar(64),
    model varchar(128),
    prompt_version varchar(64),
    generation_job_id uuid REFERENCES jobs(id),
    series_title varchar(120),
    shared_framing varchar(500),
    input_fingerprint varchar(64) NOT NULL,
    config_snapshot jsonb,
    failure_code varchar(100),
    failure_message varchar(500),
    created_by_user_id uuid NOT NULL REFERENCES app_users(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    applied_at timestamptz,
    applied_by_user_id uuid REFERENCES app_users(id),
    rejected_at timestamptz,
    rejected_by_user_id uuid REFERENCES app_users(id),
    CONSTRAINT campaign_copy_sets_revision_valid CHECK (revision > 0),
    CONSTRAINT campaign_copy_sets_status_valid CHECK (status IN (
        'GENERATING', 'READY_FOR_REVIEW', 'APPLIED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT campaign_copy_sets_run_revision_unique UNIQUE (robot_run_id, revision)
);
CREATE UNIQUE INDEX campaign_copy_sets_one_current_per_run
    ON campaign_copy_sets(robot_run_id) WHERE is_current = true;
CREATE INDEX campaign_copy_sets_workspace_created_idx
    ON campaign_copy_sets(workspace_id, created_at DESC);
CREATE UNIQUE INDEX campaign_copy_sets_generation_job_unique
    ON campaign_copy_sets(generation_job_id) WHERE generation_job_id IS NOT NULL;
CREATE INDEX campaign_copy_sets_campaign_plan_idx ON campaign_copy_sets(campaign_plan_id);

CREATE TABLE campaign_copy_items (
    id uuid PRIMARY KEY,
    copy_set_id uuid NOT NULL REFERENCES campaign_copy_sets(id) ON DELETE CASCADE,
    robot_run_output_id uuid NOT NULL REFERENCES robot_run_outputs(id) ON DELETE CASCADE,
    campaign_content_plan_item_id uuid NOT NULL REFERENCES campaign_content_plan_items(id),
    sequence integer NOT NULL,
    hook varchar(200) NOT NULL,
    caption varchar(2200) NOT NULL,
    hashtags jsonb NOT NULL DEFAULT '[]'::jsonb,
    short_title varchar(100),
    continuity_note varchar(200),
    content_suggestion_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT campaign_copy_items_sequence_valid CHECK (sequence > 0),
    CONSTRAINT campaign_copy_items_output_unique UNIQUE (copy_set_id, robot_run_output_id),
    CONSTRAINT campaign_copy_items_sequence_unique UNIQUE (copy_set_id, sequence),
    CONSTRAINT campaign_copy_items_suggestion_unique UNIQUE (content_suggestion_id)
);
CREATE INDEX campaign_copy_items_copy_set_sequence_idx ON campaign_copy_items(copy_set_id, sequence);

-- Phase 17F provenance on ContentSuggestion: populated only when the suggestion was
-- materialized from an applied CampaignCopyItem (never merely because a copy set
-- existed for the run) — mirrors the campaign_plan_* columns added in V34.
ALTER TABLE content_suggestions ADD COLUMN campaign_copy_set_id uuid;
ALTER TABLE content_suggestions ADD COLUMN campaign_copy_set_revision integer;
ALTER TABLE content_suggestions ADD COLUMN campaign_copy_item_id uuid;

-- Independent SOCIAL_COPY generation remains one Job -> one suggestion. A
-- coordinated generation Job intentionally materializes one suggestion per
-- CampaignCopyItem, so its Job id cannot be globally unique across those rows.
ALTER TABLE content_suggestions DROP CONSTRAINT content_suggestions_generation_job_unique;
CREATE UNIQUE INDEX content_suggestions_independent_generation_job_unique
    ON content_suggestions(generation_job_id)
    WHERE campaign_copy_set_id IS NULL;
CREATE UNIQUE INDEX content_suggestions_campaign_copy_item_unique
    ON content_suggestions(campaign_copy_item_id)
    WHERE campaign_copy_item_id IS NOT NULL;

ALTER TABLE publication_attributions ADD COLUMN campaign_copy_set_id uuid;
ALTER TABLE publication_attributions ADD COLUMN campaign_copy_set_revision integer;
ALTER TABLE publication_attributions ADD COLUMN campaign_copy_item_id uuid;
