ALTER TABLE social_account_credentials
    ADD COLUMN encrypted_refresh_token TEXT,
    ADD COLUMN refresh_token_expires_at TIMESTAMPTZ;

ALTER TABLE social_account_credentials DROP CONSTRAINT social_account_credentials_type_valid;
ALTER TABLE social_account_credentials ADD CONSTRAINT social_account_credentials_type_valid
    CHECK (credential_type IN ('INSTAGRAM_OAUTH2', 'TIKTOK_OAUTH2'));

ALTER TABLE social_oauth_states DROP CONSTRAINT social_oauth_states_platform_valid;
ALTER TABLE social_oauth_states ADD CONSTRAINT social_oauth_states_platform_valid
    CHECK (platform IN ('INSTAGRAM', 'TIKTOK'));

ALTER TABLE publication_provider_states DROP CONSTRAINT publication_provider_states_provider_valid;
ALTER TABLE publication_provider_states ADD CONSTRAINT publication_provider_states_provider_valid
    CHECK (provider IN ('INSTAGRAM', 'TIKTOK'));
ALTER TABLE publication_provider_states DROP CONSTRAINT publication_provider_states_state_valid;
ALTER TABLE publication_provider_states ADD CONSTRAINT publication_provider_states_state_valid
    CHECK (state IN ('CONTAINER_CREATED', 'UPLOAD_PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'OUTCOME_UNKNOWN'));
ALTER TABLE publication_provider_states ADD COLUMN provider_upload_url TEXT;

CREATE TABLE tiktok_publication_settings (
    publication_id UUID PRIMARY KEY REFERENCES publications(id) ON DELETE CASCADE,
    privacy_level TEXT NOT NULL,
    disable_comment BOOLEAN NOT NULL,
    disable_duet BOOLEAN NOT NULL,
    disable_stitch BOOLEAN NOT NULL,
    is_aigc BOOLEAN NOT NULL DEFAULT FALSE,
    brand_organic_toggle BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tiktok_privacy_level_valid CHECK (privacy_level IN (
        'PUBLIC_TO_EVERYONE', 'MUTUAL_FOLLOW_FRIENDS', 'FOLLOWER_OF_CREATOR', 'SELF_ONLY'
    ))
);

CREATE INDEX publication_provider_states_provider_state_idx
    ON publication_provider_states (provider, state, updated_at);

ALTER TABLE publish_schedules ADD COLUMN tiktok_privacy_level TEXT;
ALTER TABLE publish_schedules ADD COLUMN tiktok_disable_comment BOOLEAN;
ALTER TABLE publish_schedules ADD COLUMN tiktok_disable_duet BOOLEAN;
ALTER TABLE publish_schedules ADD COLUMN tiktok_disable_stitch BOOLEAN;
ALTER TABLE publish_schedules ADD CONSTRAINT publish_schedules_tiktok_privacy_valid CHECK (
 tiktok_privacy_level IS NULL OR tiktok_privacy_level IN ('PUBLIC_TO_EVERYONE','MUTUAL_FOLLOW_FRIENDS','FOLLOWER_OF_CREATOR','SELF_ONLY'));
