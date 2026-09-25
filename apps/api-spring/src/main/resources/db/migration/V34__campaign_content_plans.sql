ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA', 'CREATE_CLIP', 'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS', 'TRANSCRIBE_MEDIA', 'PUBLISH_MEDIA', 'GENERATE_SOCIAL_COPY', 'GENERATE_CAMPAIGN_PLAN'
));

ALTER TABLE robots ADD COLUMN campaign_planning_policy varchar(32) NOT NULL DEFAULT 'NO_CAMPAIGN_PLAN';
ALTER TABLE robots ADD CONSTRAINT robots_campaign_planning_policy_valid CHECK (campaign_planning_policy IN (
    'NO_CAMPAIGN_PLAN', 'DETERMINISTIC_PLAN', 'AI_PLAN_FOR_REVIEW', 'AI_PLAN_AND_APPLY'
));

ALTER TABLE robot_runs ADD COLUMN campaign_planning_policy_snapshot varchar(32) NOT NULL DEFAULT 'NO_CAMPAIGN_PLAN';
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_campaign_planning_policy_snapshot_valid CHECK (campaign_planning_policy_snapshot IN (
    'NO_CAMPAIGN_PLAN', 'DETERMINISTIC_PLAN', 'AI_PLAN_FOR_REVIEW', 'AI_PLAN_AND_APPLY'
));
ALTER TABLE robot_runs ADD COLUMN campaign_plan_id uuid;

CREATE TABLE campaign_content_plans (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    robot_run_id uuid NOT NULL REFERENCES robot_runs(id) ON DELETE CASCADE,
    revision integer NOT NULL,
    is_current boolean NOT NULL DEFAULT true,
    policy varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    planner_version varchar(64) NOT NULL,
    provider varchar(64),
    model varchar(128),
    prompt_version varchar(64),
    generation_job_id uuid REFERENCES jobs(id),
    campaign_title varchar(120),
    campaign_angle varchar(500),
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
    CONSTRAINT campaign_content_plans_revision_valid CHECK (revision > 0),
    CONSTRAINT campaign_content_plans_policy_valid CHECK (policy IN (
        'DETERMINISTIC_PLAN', 'AI_PLAN_FOR_REVIEW', 'AI_PLAN_AND_APPLY'
    )),
    CONSTRAINT campaign_content_plans_status_valid CHECK (status IN (
        'GENERATING', 'READY_FOR_REVIEW', 'APPLIED', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT campaign_content_plans_run_revision_unique UNIQUE (robot_run_id, revision)
);
CREATE UNIQUE INDEX campaign_content_plans_one_current_per_run
    ON campaign_content_plans(robot_run_id) WHERE is_current = true;
CREATE INDEX campaign_content_plans_workspace_created_idx
    ON campaign_content_plans(workspace_id, created_at DESC);
CREATE UNIQUE INDEX campaign_content_plans_generation_job_unique
    ON campaign_content_plans(generation_job_id) WHERE generation_job_id IS NOT NULL;

CREATE TABLE campaign_content_plan_items (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES campaign_content_plans(id) ON DELETE CASCADE,
    robot_run_output_id uuid NOT NULL REFERENCES robot_run_outputs(id) ON DELETE CASCADE,
    sequence integer NOT NULL,
    role varchar(32) NOT NULL,
    hook_guidance varchar(300),
    caption_guidance varchar(800),
    cta_guidance varchar(300),
    avoid_repetition_guidance varchar(500),
    created_at timestamptz NOT NULL,
    CONSTRAINT campaign_content_plan_items_sequence_valid CHECK (sequence > 0),
    CONSTRAINT campaign_content_plan_items_role_valid CHECK (role IN (
        'INTRODUCTION', 'DEEP_DIVE', 'SUPPORTING_POINT', 'CONCLUSION', 'STANDALONE'
    )),
    CONSTRAINT campaign_content_plan_items_output_unique UNIQUE (plan_id, robot_run_output_id),
    CONSTRAINT campaign_content_plan_items_sequence_unique UNIQUE (plan_id, sequence)
);
CREATE INDEX campaign_content_plan_items_plan_sequence_idx
    ON campaign_content_plan_items(plan_id, sequence);

ALTER TABLE content_suggestions ADD COLUMN campaign_plan_id uuid;
ALTER TABLE content_suggestions ADD COLUMN campaign_plan_revision integer;
ALTER TABLE content_suggestions ADD COLUMN campaign_plan_item_id uuid;
-- Frozen guidance snapshot (mirrors the persona_* columns above): the fingerprint/staleness
-- recheck reads only these, never a fresh CampaignContentPlanItem lookup.
ALTER TABLE content_suggestions ADD COLUMN campaign_role varchar(32);
ALTER TABLE content_suggestions ADD COLUMN campaign_hook_guidance varchar(300);
ALTER TABLE content_suggestions ADD COLUMN campaign_caption_guidance varchar(800);
ALTER TABLE content_suggestions ADD COLUMN campaign_cta_guidance varchar(300);
ALTER TABLE content_suggestions ADD COLUMN campaign_avoid_repetition_guidance varchar(500);

ALTER TABLE publication_attributions ADD COLUMN campaign_plan_id uuid;
ALTER TABLE publication_attributions ADD COLUMN campaign_plan_revision integer;
ALTER TABLE publication_attributions ADD COLUMN campaign_plan_item_id uuid;
