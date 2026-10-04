ALTER TABLE robot_adaptive_policies
    ADD COLUMN proposal_automation_mode varchar(24) NOT NULL DEFAULT 'MANUAL_ONLY'
    CHECK (proposal_automation_mode IN ('MANUAL_ONLY', 'AUTO_PROPOSE'));

ALTER TABLE optimization_proposals
    ADD COLUMN origin varchar(24) NOT NULL DEFAULT 'MANUAL'
        CHECK (origin IN ('MANUAL', 'AUTO_PROPOSE')),
    ADD COLUMN automation_engine_version varchar(64),
    ADD COLUMN automation_robot_id uuid REFERENCES robots(id) ON DELETE RESTRICT,
    ADD COLUMN automation_policy_revision integer CHECK (automation_policy_revision IS NULL OR automation_policy_revision > 0),
    ADD COLUMN automation_trigger varchar(32),
    ADD COLUMN automation_opportunity_fingerprint varchar(64),
    ADD CONSTRAINT optimization_proposals_automation_fingerprint_length_ck
        CHECK (automation_opportunity_fingerprint IS NULL OR length(automation_opportunity_fingerprint) = 64),
    ADD CONSTRAINT optimization_proposals_automation_provenance_ck
        CHECK (origin = 'MANUAL' OR (
            automation_engine_version = 'AUTONOMOUS_PROPOSALS_V1'
            AND automation_robot_id IS NOT NULL
            AND automation_policy_revision IS NOT NULL
            AND automation_trigger IS NOT NULL
            AND automation_opportunity_fingerprint IS NOT NULL
        ));

CREATE UNIQUE INDEX optimization_proposals_automation_opportunity_unique
    ON optimization_proposals(automation_opportunity_fingerprint)
    WHERE automation_opportunity_fingerprint IS NOT NULL;

CREATE INDEX robot_adaptive_policies_auto_propose_idx
    ON robot_adaptive_policies(robot_id)
    WHERE enabled AND proposal_automation_mode = 'AUTO_PROPOSE';

CREATE INDEX optimization_proposals_automation_robot_status_idx
    ON optimization_proposals(automation_robot_id, status, created_at DESC)
    WHERE automation_robot_id IS NOT NULL;

