ALTER TABLE highlight_analyses
    ADD COLUMN config_fingerprint varchar(64),
    ADD COLUMN config_snapshot jsonb,
    ADD COLUMN transcript_coverage numeric(5,4),
    ADD CONSTRAINT highlight_analyses_transcript_coverage_valid
        CHECK (transcript_coverage IS NULL OR (transcript_coverage >= 0 AND transcript_coverage <= 1));

CREATE INDEX idx_highlight_analyses_workspace_asset_type_fingerprint
    ON highlight_analyses(workspace_id, asset_id, analyzer_type, config_fingerprint);

ALTER TABLE highlight_candidates
    ADD COLUMN hook_score numeric(5,4),
    ADD COLUMN completeness_score numeric(5,4),
    ADD COLUMN information_density_score numeric(5,4),
    ADD COLUMN speech_density_score numeric(5,4),
    ADD COLUMN boundary_score numeric(5,4),
    ADD COLUMN coverage_score numeric(5,4),
    ADD COLUMN scene_score numeric(5,4),
    ADD COLUMN audio_boundary_score numeric(5,4),
    ADD COLUMN repetition_penalty numeric(5,4),
    ADD COLUMN explanation_labels jsonb,
    ADD COLUMN transcript_excerpt varchar(500),
    ADD CONSTRAINT highlight_candidates_hook_score_valid
        CHECK (hook_score IS NULL OR (hook_score >= 0 AND hook_score <= 1)),
    ADD CONSTRAINT highlight_candidates_completeness_score_valid
        CHECK (completeness_score IS NULL OR (completeness_score >= 0 AND completeness_score <= 1)),
    ADD CONSTRAINT highlight_candidates_information_density_score_valid
        CHECK (information_density_score IS NULL OR (information_density_score >= 0 AND information_density_score <= 1)),
    ADD CONSTRAINT highlight_candidates_speech_density_score_valid
        CHECK (speech_density_score IS NULL OR (speech_density_score >= 0 AND speech_density_score <= 1)),
    ADD CONSTRAINT highlight_candidates_boundary_score_valid
        CHECK (boundary_score IS NULL OR (boundary_score >= 0 AND boundary_score <= 1)),
    ADD CONSTRAINT highlight_candidates_coverage_score_valid
        CHECK (coverage_score IS NULL OR (coverage_score >= 0 AND coverage_score <= 1)),
    ADD CONSTRAINT highlight_candidates_scene_score_valid
        CHECK (scene_score IS NULL OR (scene_score >= 0 AND scene_score <= 1)),
    ADD CONSTRAINT highlight_candidates_audio_boundary_score_valid
        CHECK (audio_boundary_score IS NULL OR (audio_boundary_score >= 0 AND audio_boundary_score <= 1)),
    ADD CONSTRAINT highlight_candidates_repetition_penalty_valid
        CHECK (repetition_penalty IS NULL OR (repetition_penalty >= 0 AND repetition_penalty <= 1));
