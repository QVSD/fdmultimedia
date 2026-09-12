ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN ('SYSTEM_TEST', 'IMPORT_MEDIA', 'INSPECT_MEDIA', 'CREATE_CLIP'));

ALTER TABLE media_assets DROP CONSTRAINT media_assets_source_type_valid;
ALTER TABLE media_assets DROP CONSTRAINT media_assets_status_valid;

ALTER TABLE media_assets
    ADD COLUMN parent_asset_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    ADD COLUMN derivation_type TEXT NOT NULL DEFAULT 'ORIGINAL',
    ADD COLUMN processing_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_source_type_valid CHECK (source_type IN ('DIRECT_URL', 'DERIVED')),
    ADD CONSTRAINT media_assets_status_valid CHECK (status IN ('PENDING', 'IMPORTING', 'PROCESSING', 'READY', 'FAILED')),
    ADD CONSTRAINT media_assets_derivation_type_valid CHECK (derivation_type IN ('ORIGINAL', 'CLIP')),
    ADD CONSTRAINT media_assets_derivative_has_parent CHECK (
        (derivation_type = 'ORIGINAL' AND parent_asset_id IS NULL)
        OR
        (derivation_type <> 'ORIGINAL' AND parent_asset_id IS NOT NULL)
    );

CREATE INDEX media_assets_parent_asset_idx ON media_assets (parent_asset_id);
CREATE INDEX media_assets_processing_job_idx ON media_assets (processing_job_id);
