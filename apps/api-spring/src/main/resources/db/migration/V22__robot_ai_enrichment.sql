-- Robot AI enrichment policy: an independent axis from autonomy_mode (never
-- collapse the two). NO_AI is the historically-safe default for every
-- existing Robot — behavior is byte-for-byte unchanged from Phase 11D/12B.
ALTER TABLE robots ADD COLUMN ai_policy TEXT NOT NULL DEFAULT 'NO_AI';
ALTER TABLE robots ADD COLUMN persona_id UUID REFERENCES personas(id) ON DELETE SET NULL;
ALTER TABLE robots ADD COLUMN ai_language_override TEXT;
ALTER TABLE robots ADD COLUMN ai_tone_override TEXT;

ALTER TABLE robots ADD CONSTRAINT robots_ai_policy_valid
    CHECK (ai_policy IN ('NO_AI', 'GENERATE_FOR_REVIEW', 'GENERATE_AND_APPLY'));
ALTER TABLE robots ADD CONSTRAINT robots_ai_language_override_valid
    CHECK (ai_language_override IS NULL OR ai_language_override IN ('AUTO', 'ENGLISH', 'ROMANIAN'));
ALTER TABLE robots ADD CONSTRAINT robots_ai_tone_override_valid
    CHECK (ai_tone_override IS NULL OR ai_tone_override IN ('NEUTRAL', 'INFORMATIVE', 'CASUAL', 'ENERGETIC'));
-- A NO_AI Robot must not carry Persona/override configuration it will never
-- use — avoids misleading dead config, mirrors the
-- robots_source_config_matches_policy discipline from V19.
ALTER TABLE robots ADD CONSTRAINT robots_ai_config_matches_policy CHECK (
    (ai_policy = 'NO_AI' AND persona_id IS NULL AND ai_language_override IS NULL AND ai_tone_override IS NULL)
    OR ai_policy <> 'NO_AI'
);

CREATE INDEX robots_persona_idx ON robots (persona_id) WHERE persona_id IS NOT NULL;

-- RobotRun captures an immutable snapshot of the AI configuration at run
-- creation time (see RobotRunOrchestrator/RobotAutomationDispatchService) —
-- editing a Robot's AI policy/Persona while a run is in flight never
-- redirects that run; only future runs see the new configuration.
-- persona_id_snapshot is a plain reference (which Persona to resolve), not
-- the Persona's editorial fields themselves — those are only ever
-- snapshotted onto the ContentSuggestion itself, exactly as Phase 12B
-- established; RobotRun never duplicates Persona editorial content.
ALTER TABLE robot_runs ADD COLUMN ai_policy_snapshot TEXT NOT NULL DEFAULT 'NO_AI';
ALTER TABLE robot_runs ADD COLUMN persona_id_snapshot UUID;
ALTER TABLE robot_runs ADD COLUMN ai_language_override_snapshot TEXT;
ALTER TABLE robot_runs ADD COLUMN ai_tone_override_snapshot TEXT;
ALTER TABLE robot_runs ADD COLUMN content_suggestion_id UUID;

ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_ai_policy_snapshot_valid
    CHECK (ai_policy_snapshot IN ('NO_AI', 'GENERATE_FOR_REVIEW', 'GENERATE_AND_APPLY'));
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_ai_language_override_snapshot_valid
    CHECK (ai_language_override_snapshot IS NULL OR ai_language_override_snapshot IN ('AUTO', 'ENGLISH', 'ROMANIAN'));
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_ai_tone_override_snapshot_valid
    CHECK (ai_tone_override_snapshot IS NULL OR ai_tone_override_snapshot IN ('NEUTRAL', 'INFORMATIVE', 'CASUAL', 'ENERGETIC'));

CREATE INDEX robot_runs_content_suggestion_idx ON robot_runs (content_suggestion_id) WHERE content_suggestion_id IS NOT NULL;

-- Every phase that introduces a new RobotRun status re-declares this
-- constraint (established by V18) — WAITING_FOR_AI (AI Job/suggestion still
-- processing) and WAITING_FOR_AI_REVIEW (suggestion READY, human review
-- required) join the existing list. WAITING_FOR_REVIEW remains exclusively
-- the pre-existing *publishing approval* review gate — these are two
-- deliberately separate human review concepts, never conflated.
ALTER TABLE robot_runs DROP CONSTRAINT robot_runs_status_valid;
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_status_valid CHECK (status IN (
    'RUNNING', 'WAITING_FOR_DRAFT', 'WAITING_FOR_AI', 'WAITING_FOR_AI_REVIEW',
    'WAITING_FOR_REVIEW', 'SUCCEEDED', 'FAILED', 'CANCELLED'
));

-- ContentSuggestion provenance: explicit, never inferred. Existing rows
-- (Phase 12A/12B) become MANUAL with robot_run_id forced to NULL — under
-- the pre-12C code, robot_run_id was auto-inherited from the ContentDraft's
-- own Robot provenance regardless of who actually triggered generation,
-- which conflated "this Draft came from a Robot" with "a Robot generated
-- this suggestion." Phase 12C corrects this: robot_run_id on a
-- ContentSuggestion now means only "the RobotRun that itself generated
-- this suggestion" (see ContentSuggestion.forRobot), so historical rows —
-- all of which were human-initiated regardless of the Draft's own origin —
-- are backward-safely normalized to MANUAL/NULL, never silently
-- reinterpreted as Robot-authored.
ALTER TABLE content_suggestions ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL';
UPDATE content_suggestions SET robot_run_id = NULL WHERE origin = 'MANUAL';

ALTER TABLE content_suggestions ADD CONSTRAINT content_suggestions_origin_valid
    CHECK (origin IN ('MANUAL', 'ROBOT'));
ALTER TABLE content_suggestions ADD CONSTRAINT content_suggestions_origin_robot_run_id_consistent CHECK (
    (origin = 'MANUAL' AND robot_run_id IS NULL) OR (origin = 'ROBOT' AND robot_run_id IS NOT NULL)
);
-- Defense in depth against duplicate reconciliation: at most one automatic
-- (ROBOT-origin) suggestion may ever exist per RobotRun, enforced at the
-- database level, not merely by application-level idempotency checks.
CREATE UNIQUE INDEX content_suggestions_one_robot_suggestion_per_run
    ON content_suggestions (robot_run_id) WHERE origin = 'ROBOT';
