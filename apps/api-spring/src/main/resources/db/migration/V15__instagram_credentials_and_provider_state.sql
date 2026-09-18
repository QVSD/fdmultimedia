-- Prevent duplicate active connections of the same external provider account
-- to the same workspace. NULL external_account_id (e.g. TEST accounts) is
-- intentionally excluded from this constraint since TEST has no external
-- identity and users may legitimately create many named TEST accounts.
CREATE UNIQUE INDEX social_accounts_workspace_platform_external_id_idx
    ON social_accounts (workspace_id, platform, external_account_id)
    WHERE external_account_id IS NOT NULL;

-- Credential boundary promised in V14/Phase 10A: provider access tokens live
-- here, never on social_accounts, never in a job payload, never returned to
-- the browser. encrypted_access_token is AES-GCM ciphertext (versioned
-- format, random nonce per encryption) produced by CredentialEncryptionService;
-- the decryption key is supplied only through deployment configuration
-- (SOCIAL_CREDENTIAL_ENCRYPTION_KEY) and is never stored in this database.
CREATE TABLE social_account_credentials (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    social_account_id     UUID NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
    credential_type       TEXT NOT NULL,
    encrypted_access_token TEXT NOT NULL,
    token_expires_at      TIMESTAMPTZ,
    scopes                TEXT,
    last_validated_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT social_account_credentials_type_valid CHECK (credential_type IN ('INSTAGRAM_OAUTH2')),
    CONSTRAINT social_account_credentials_account_unique UNIQUE (social_account_id)
);

-- Short-lived, single-use, server-validated OAuth state. Only a hash of the
-- random state token is stored (the raw token lives only in the redirect URL
-- given to the browser), so a database read alone cannot forge a valid
-- callback. Authorization context (workspace/user) is looked up from this row
-- during the callback, never trusted from callback query parameters.
CREATE TABLE social_oauth_states (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    state_hash      TEXT NOT NULL UNIQUE,
    platform        TEXT NOT NULL,
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT social_oauth_states_platform_valid CHECK (platform IN ('INSTAGRAM'))
);

CREATE INDEX social_oauth_states_expires_idx ON social_oauth_states (expires_at);

-- Durable reconciliation ledger for external, side-effectful provider
-- operations that lack a native idempotency key. Before a container/post is
-- created on the provider, no row exists. As soon as the provider confirms a
-- container id, it is persisted here so a retry after a crash/lost-response
-- reuses that same container instead of creating a duplicate external post.
-- Never stores access tokens or raw provider response bodies.
CREATE TABLE publication_provider_states (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id        UUID NOT NULL REFERENCES publications(id) ON DELETE CASCADE,
    provider              TEXT NOT NULL,
    provider_container_id TEXT,
    provider_media_id     TEXT,
    state                 TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT publication_provider_states_publication_unique UNIQUE (publication_id),
    CONSTRAINT publication_provider_states_provider_valid CHECK (provider IN ('INSTAGRAM')),
    CONSTRAINT publication_provider_states_state_valid CHECK (state IN ('CONTAINER_CREATED', 'PUBLISHED'))
);
