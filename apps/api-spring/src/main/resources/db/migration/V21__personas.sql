CREATE TABLE personas (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name                TEXT NOT NULL,
    description         TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    default_language    TEXT NOT NULL DEFAULT 'AUTO',
    default_tone        TEXT NOT NULL DEFAULT 'NEUTRAL',
    audience            TEXT,
    voice_description   TEXT NOT NULL,
    style_guidelines    TEXT,
    avoid_guidelines    TEXT,
    hashtag_guidelines  TEXT,
    example_copy        TEXT,
    created_by_user_id  UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT personas_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT personas_default_language_valid CHECK (default_language IN ('AUTO', 'ENGLISH', 'ROMANIAN')),
    CONSTRAINT personas_default_tone_valid CHECK (default_tone IN ('NEUTRAL', 'INFORMATIVE', 'CASUAL', 'ENERGETIC')),
    CONSTRAINT personas_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT personas_voice_description_not_blank CHECK (btrim(voice_description) <> ''),
    CONSTRAINT personas_name_length CHECK (char_length(name) <= 100),
    CONSTRAINT personas_description_length CHECK (description IS NULL OR char_length(description) <= 500),
    CONSTRAINT personas_audience_length CHECK (audience IS NULL OR char_length(audience) <= 500),
    CONSTRAINT personas_voice_description_length CHECK (char_length(voice_description) <= 1000),
    CONSTRAINT personas_style_guidelines_length CHECK (style_guidelines IS NULL OR char_length(style_guidelines) <= 2000),
    CONSTRAINT personas_avoid_guidelines_length CHECK (avoid_guidelines IS NULL OR char_length(avoid_guidelines) <= 2000),
    CONSTRAINT personas_hashtag_guidelines_length CHECK (hashtag_guidelines IS NULL OR char_length(hashtag_guidelines) <= 1000),
    CONSTRAINT personas_example_copy_length CHECK (example_copy IS NULL OR char_length(example_copy) <= 2000)
);

CREATE INDEX personas_workspace_idx ON personas (workspace_id, created_at DESC);
CREATE INDEX personas_workspace_active_idx ON personas (workspace_id) WHERE status = 'ACTIVE';

-- Immutable Persona snapshot captured on a ContentSuggestion at generation
-- time. Deliberately no FK to personas(id): a suggestion's historical
-- explanation must never depend on that Persona row still existing,
-- remaining ACTIVE, or keeping its original field values (see
-- ContentSuggestionService — apply()/toSummary() recompute the fingerprint
-- from these snapshot columns only, never by re-reading the live Persona).
-- persona_id is still recorded (nullable) purely for audit/filtering, the
-- same pattern content_suggestions.robot_run_id already uses for RobotRun.
ALTER TABLE content_suggestions ADD COLUMN persona_id UUID;
ALTER TABLE content_suggestions ADD COLUMN persona_name TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_audience TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_voice_description TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_style_guidelines TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_avoid_guidelines TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_hashtag_guidelines TEXT;
ALTER TABLE content_suggestions ADD COLUMN persona_example_copy TEXT;

CREATE INDEX content_suggestions_persona_idx ON content_suggestions (persona_id) WHERE persona_id IS NOT NULL;
