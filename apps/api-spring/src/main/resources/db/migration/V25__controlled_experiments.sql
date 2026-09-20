-- Phase 14A: Controlled Experiments & A/B Testing Foundation.
-- An Experiment is a controlled assignment mechanism, not an optimizer: it
-- freezes exactly two variants (A/B) of one factor (PERSONA in this phase)
-- and durably records which RobotRun was assigned which variant, before any
-- performance outcome exists. See docs/ARCHITECTURE.md for the full design.

CREATE TABLE experiments (
    id                          UUID PRIMARY KEY,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    description                 TEXT,
    hypothesis                  TEXT NOT NULL,
    factor                      TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'DRAFT',
    assignment_strategy         TEXT NOT NULL DEFAULT 'DETERMINISTIC_BALANCED_V1',
    target_observation_window   TEXT NOT NULL,
    primary_metric              TEXT NOT NULL,
    created_by_user_id          UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    activated_at                TIMESTAMPTZ,
    stopped_at                  TIMESTAMPTZ,
    CONSTRAINT experiments_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT experiments_hypothesis_not_blank CHECK (btrim(hypothesis) <> ''),
    CONSTRAINT experiments_factor_valid CHECK (factor IN ('PERSONA')),
    CONSTRAINT experiments_status_valid CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT experiments_strategy_valid CHECK (assignment_strategy IN ('DETERMINISTIC_BALANCED_V1')),
    -- LATEST is deliberately excluded: it gives different maturity across
    -- publications and is unsuitable as a controlled experiment's fixed
    -- outcome definition (see docs/ARCHITECTURE.md).
    CONSTRAINT experiments_window_valid CHECK (target_observation_window IN ('H24', 'H72', 'D7')),
    CONSTRAINT experiments_metric_valid CHECK (primary_metric IN
        ('VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'))
);
CREATE INDEX experiments_workspace_status_idx ON experiments (workspace_id, status);

-- Exactly two variants per experiment (A/B only — no multivariate). The
-- Persona treatment snapshot columns are populated once, at activation
-- (ExperimentService.activate), from the exact same immutable-copy pattern
-- Phase 12B established for ContentSuggestion's own Persona snapshot — see
-- PersonaSnapshot.java. Deliberately no FK to personas(id): a frozen
-- treatment must remain valid after that Persona is later archived, exactly
-- like content_suggestions.persona_id.
CREATE TABLE experiment_variants (
    id                                      UUID PRIMARY KEY,
    experiment_id                           UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    variant_key                             TEXT NOT NULL,
    label                                   TEXT NOT NULL,
    persona_id                              UUID NOT NULL,
    persona_name_snapshot                   TEXT,
    persona_default_language_snapshot       TEXT,
    persona_default_tone_snapshot           TEXT,
    persona_audience_snapshot               TEXT,
    persona_voice_description_snapshot      TEXT,
    persona_style_guidelines_snapshot       TEXT,
    persona_avoid_guidelines_snapshot       TEXT,
    persona_hashtag_guidelines_snapshot     TEXT,
    persona_example_copy_snapshot           TEXT,
    allocation_weight                       INT NOT NULL DEFAULT 1,
    created_at                              TIMESTAMPTZ NOT NULL,
    CONSTRAINT experiment_variants_key_valid CHECK (variant_key IN ('A', 'B')),
    CONSTRAINT experiment_variants_unique_key UNIQUE (experiment_id, variant_key)
);
CREATE INDEX experiment_variants_experiment_idx ON experiment_variants (experiment_id);

-- The durable, immutable record of one RobotRun's controlled assignment —
-- inserted exactly once, in the same transaction as the RobotRun itself
-- (RobotAutomationDispatchService.startRun), always before the treatment is
-- ever consumed. robot_run_id has no FK: RobotRun and ExperimentAssignment
-- are created together in one transaction and neither is ever deleted, so a
-- dangling reference cannot occur in practice, and this keeps the
-- experiments package free of a compile-time dependency on robots.
CREATE TABLE experiment_assignments (
    id                          UUID PRIMARY KEY,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    experiment_id               UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    experiment_variant_id       UUID NOT NULL REFERENCES experiment_variants(id) ON DELETE CASCADE,
    robot_run_id                UUID NOT NULL,
    assigned_at                 TIMESTAMPTZ NOT NULL,
    assignment_strategy         TEXT NOT NULL,
    factor                      TEXT NOT NULL,
    factor_value_id             UUID,
    factor_value_name_snapshot  TEXT NOT NULL,
    CONSTRAINT experiment_assignments_robot_run_unique UNIQUE (robot_run_id)
);
CREATE INDEX experiment_assignments_experiment_variant_idx ON experiment_assignments (experiment_id, experiment_variant_id);

-- Robot opt-in (item 13): a plain reference, never a join relationship in
-- Java, so the experiments package stays independent of the robots package.
ALTER TABLE robots ADD COLUMN experiment_id UUID REFERENCES experiments(id) ON DELETE SET NULL;
CREATE INDEX robots_experiment_idx ON robots (experiment_id) WHERE experiment_id IS NOT NULL;

-- RobotRun's own immutable snapshot of the assignment it received at
-- creation (see RobotRun/RobotAutomationDispatchService) — the
-- experiment_assignments row above remains the source of truth; these
-- columns are audit convenience only, exactly like ai_policy_snapshot.
ALTER TABLE robot_runs ADD COLUMN experiment_id UUID;
ALTER TABLE robot_runs ADD COLUMN experiment_assignment_id UUID;
ALTER TABLE robot_runs ADD COLUMN experiment_variant_id UUID;
ALTER TABLE robot_runs ADD COLUMN experiment_variant_key TEXT;
ALTER TABLE robot_runs ADD COLUMN experiment_factor TEXT;
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_experiment_variant_key_valid
    CHECK (experiment_variant_key IS NULL OR experiment_variant_key IN ('A', 'B'));
ALTER TABLE robot_runs ADD CONSTRAINT robot_runs_experiment_factor_valid
    CHECK (experiment_factor IS NULL OR experiment_factor IN ('PERSONA'));
CREATE INDEX robot_runs_experiment_idx ON robot_runs (experiment_id) WHERE experiment_id IS NOT NULL;

-- ContentSuggestion provenance (item 29): proves which suggestion was
-- generated under which experimental treatment, so a later manual
-- regeneration/apply can be distinguished from the automatic treatment
-- suggestion (see PublicationAttributionService's protocol-deviation logic).
ALTER TABLE content_suggestions ADD COLUMN experiment_id UUID;
ALTER TABLE content_suggestions ADD COLUMN experiment_assignment_id UUID;
ALTER TABLE content_suggestions ADD COLUMN experiment_variant_id UUID;
CREATE INDEX content_suggestions_experiment_idx ON content_suggestions (experiment_id) WHERE experiment_id IS NOT NULL;

-- Publication attribution provenance (item 33): frozen at publish time from
-- the RobotRun/ContentSuggestion chain, never re-derived later from a live
-- (and possibly since-renamed) Experiment/Variant row. protocol_deviation is
-- purely descriptive (item 32/97) — a deviating Publication is never
-- automatically excluded from anything.
ALTER TABLE publication_attributions ADD COLUMN experiment_id UUID;
ALTER TABLE publication_attributions ADD COLUMN experiment_name_snapshot TEXT;
ALTER TABLE publication_attributions ADD COLUMN experiment_factor TEXT;
ALTER TABLE publication_attributions ADD COLUMN experiment_variant_id UUID;
ALTER TABLE publication_attributions ADD COLUMN experiment_variant_key TEXT;
ALTER TABLE publication_attributions ADD COLUMN experiment_variant_label_snapshot TEXT;
ALTER TABLE publication_attributions ADD COLUMN experiment_factor_value_id UUID;
ALTER TABLE publication_attributions ADD COLUMN experiment_factor_value_name_snapshot TEXT;
ALTER TABLE publication_attributions ADD COLUMN experiment_assignment_id UUID;
ALTER TABLE publication_attributions ADD COLUMN protocol_deviation BOOLEAN;
ALTER TABLE publication_attributions ADD COLUMN protocol_deviation_reason TEXT;
CREATE INDEX publication_attributions_experiment_variant_idx
    ON publication_attributions (experiment_id, experiment_variant_id) WHERE experiment_id IS NOT NULL;
