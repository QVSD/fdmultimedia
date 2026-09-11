ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN ('SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA'));

ALTER TABLE media_assets
    ADD COLUMN inspection_status TEXT NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN inspection_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    ADD COLUMN inspection_error_code TEXT,
    ADD COLUMN inspection_error_message TEXT,
    ADD COLUMN frame_rate NUMERIC(10, 3),
    ADD COLUMN bitrate BIGINT,
    ADD COLUMN has_video BOOLEAN,
    ADD COLUMN has_audio BOOLEAN;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_inspection_status_valid
        CHECK (inspection_status IN ('NOT_REQUESTED', 'PENDING', 'INSPECTING', 'INSPECTED', 'FAILED')),
    ADD CONSTRAINT media_assets_inspection_error_code_not_blank
        CHECK (inspection_error_code IS NULL OR btrim(inspection_error_code) <> ''),
    ADD CONSTRAINT media_assets_inspection_error_message_not_blank
        CHECK (inspection_error_message IS NULL OR btrim(inspection_error_message) <> ''),
    ADD CONSTRAINT media_assets_frame_rate_positive
        CHECK (frame_rate IS NULL OR frame_rate > 0),
    ADD CONSTRAINT media_assets_bitrate_non_negative
        CHECK (bitrate IS NULL OR bitrate >= 0);

CREATE INDEX media_assets_inspection_job_idx ON media_assets (inspection_job_id);
CREATE INDEX media_assets_workspace_inspection_status_idx ON media_assets (workspace_id, inspection_status);
