ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN ('SYSTEM_TEST', 'IMPORT_MEDIA'));

CREATE TABLE media_assets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_type         TEXT NOT NULL,
    source_url          TEXT NOT NULL,
    status              TEXT NOT NULL,
    original_filename   TEXT,
    content_type        TEXT,
    file_size_bytes     BIGINT,
    checksum_sha256     TEXT,
    storage_bucket      TEXT,
    storage_key         TEXT,
    duration_ms         BIGINT,
    width               INTEGER,
    height              INTEGER,
    video_codec         TEXT,
    audio_codec         TEXT,
    container_format    TEXT,
    created_by_user_id  UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    import_job_id       UUID REFERENCES jobs(id) ON DELETE SET NULL,
    error_code          TEXT,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at            TIMESTAMPTZ,
    CONSTRAINT media_assets_source_type_valid CHECK (source_type IN ('DIRECT_URL')),
    CONSTRAINT media_assets_status_valid CHECK (status IN ('PENDING', 'IMPORTING', 'READY', 'FAILED')),
    CONSTRAINT media_assets_source_url_not_blank CHECK (btrim(source_url) <> ''),
    CONSTRAINT media_assets_original_filename_not_blank CHECK (original_filename IS NULL OR btrim(original_filename) <> ''),
    CONSTRAINT media_assets_content_type_not_blank CHECK (content_type IS NULL OR btrim(content_type) <> ''),
    CONSTRAINT media_assets_file_size_non_negative CHECK (file_size_bytes IS NULL OR file_size_bytes >= 0),
    CONSTRAINT media_assets_checksum_sha256_format CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT media_assets_storage_bucket_not_blank CHECK (storage_bucket IS NULL OR btrim(storage_bucket) <> ''),
    CONSTRAINT media_assets_storage_key_not_blank CHECK (storage_key IS NULL OR btrim(storage_key) <> ''),
    CONSTRAINT media_assets_duration_non_negative CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT media_assets_width_positive CHECK (width IS NULL OR width > 0),
    CONSTRAINT media_assets_height_positive CHECK (height IS NULL OR height > 0),
    CONSTRAINT media_assets_error_code_not_blank CHECK (error_code IS NULL OR btrim(error_code) <> ''),
    CONSTRAINT media_assets_error_message_not_blank CHECK (error_message IS NULL OR btrim(error_message) <> '')
);

CREATE INDEX media_assets_workspace_created_at_idx ON media_assets (workspace_id, created_at DESC);
CREATE INDEX media_assets_workspace_status_idx ON media_assets (workspace_id, status);
CREATE INDEX media_assets_checksum_idx ON media_assets (checksum_sha256);
CREATE INDEX media_assets_import_job_idx ON media_assets (import_job_id);
