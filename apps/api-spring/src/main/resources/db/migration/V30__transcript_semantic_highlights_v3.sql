ALTER TABLE highlight_analyses
    ADD COLUMN requested_analyzer_type varchar(80),
    ADD COLUMN effective_analyzer_type varchar(80),
    ADD COLUMN fallback_reason varchar(80),
    ADD COLUMN transcript_id uuid REFERENCES media_transcripts(id);

ALTER TABLE highlight_candidates
    ADD COLUMN base_score numeric(5,4),
    ADD COLUMN lexical_score numeric(5,4),
    ADD COLUMN emphasis_score numeric(5,4),
    ADD COLUMN self_contained_score numeric(5,4),
    ADD COLUMN semantic_score numeric(5,4),
    ADD COLUMN word_count integer,
    ADD COLUMN first_transcript_segment_id uuid REFERENCES transcript_segments(id),
    ADD COLUMN last_transcript_segment_id uuid REFERENCES transcript_segments(id),
    ADD COLUMN boundary_start_adjustment_ms bigint,
    ADD COLUMN boundary_end_adjustment_ms bigint,
    ADD CONSTRAINT highlight_candidates_v3_base_score_valid CHECK (base_score IS NULL OR (base_score >= 0 AND base_score <= 1)),
    ADD CONSTRAINT highlight_candidates_v3_lexical_score_valid CHECK (lexical_score IS NULL OR (lexical_score >= 0 AND lexical_score <= 1)),
    ADD CONSTRAINT highlight_candidates_v3_emphasis_score_valid CHECK (emphasis_score IS NULL OR (emphasis_score >= 0 AND emphasis_score <= 1)),
    ADD CONSTRAINT highlight_candidates_v3_self_contained_score_valid CHECK (self_contained_score IS NULL OR (self_contained_score >= 0 AND self_contained_score <= 1)),
    ADD CONSTRAINT highlight_candidates_v3_semantic_score_valid CHECK (semantic_score IS NULL OR (semantic_score >= 0 AND semantic_score <= 1)),
    ADD CONSTRAINT highlight_candidates_v3_word_count_valid CHECK (word_count IS NULL OR word_count >= 0);

CREATE INDEX idx_highlight_analyses_transcript_id ON highlight_analyses(transcript_id);
