ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST',
    'IMPORT_MEDIA',
    'INSPECT_MEDIA',
    'CREATE_CLIP',
    'CREATE_SOCIAL_VERTICAL'
));

ALTER TABLE media_assets DROP CONSTRAINT media_assets_derivation_type_valid;
ALTER TABLE media_assets ADD CONSTRAINT media_assets_derivation_type_valid CHECK (
    derivation_type IN ('ORIGINAL', 'CLIP', 'SOCIAL_VERTICAL')
);
