ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST',
    'IMPORT_MEDIA',
    'INSPECT_MEDIA',
    'CREATE_CLIP',
    'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS',
    'TRANSCRIBE_MEDIA'
));

CREATE TABLE media_transcripts (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    asset_id uuid NOT NULL REFERENCES media_assets(id),
    status varchar(32) NOT NULL,
    transcription_job_id uuid NOT NULL REFERENCES jobs(id),
    provider varchar(64) NOT NULL,
    model varchar(128) NOT NULL,
    detected_language varchar(32),
    duration_ms bigint,
    error_code varchar(100),
    error_message varchar(500),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT media_transcripts_status_valid CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT media_transcripts_duration_valid CHECK (duration_ms IS NULL OR duration_ms > 0)
);

CREATE TABLE transcript_segments (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    transcript_id uuid NOT NULL REFERENCES media_transcripts(id) ON DELETE CASCADE,
    sequence integer NOT NULL,
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    text varchar(4000) NOT NULL,
    confidence numeric(6,5),
    created_at timestamptz NOT NULL,
    CONSTRAINT transcript_segments_interval_valid CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT transcript_segments_sequence_valid CHECK (sequence > 0),
    CONSTRAINT transcript_segments_text_valid CHECK (length(trim(text)) > 0),
    CONSTRAINT transcript_segments_confidence_valid CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT transcript_segments_sequence_unique UNIQUE (transcript_id, sequence)
);

CREATE INDEX idx_media_transcripts_workspace_asset_created
    ON media_transcripts(workspace_id, asset_id, created_at DESC);

CREATE INDEX idx_media_transcripts_workspace_status
    ON media_transcripts(workspace_id, status);

CREATE INDEX idx_media_transcripts_job
    ON media_transcripts(transcription_job_id);

CREATE INDEX idx_media_transcripts_active_model
    ON media_transcripts(workspace_id, asset_id, provider, model, status)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_transcript_segments_transcript_sequence
    ON transcript_segments(transcript_id, sequence);
