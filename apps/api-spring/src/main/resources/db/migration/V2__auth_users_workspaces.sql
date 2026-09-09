CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_users_email_not_blank CHECK (btrim(email) <> ''),
    CONSTRAINT app_users_password_hash_not_blank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT app_users_display_name_not_blank CHECK (btrim(display_name) <> '')
);

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL,
    slug       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspaces_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT workspaces_slug_not_blank CHECK (btrim(slug) <> ''),
    CONSTRAINT workspaces_slug_unique UNIQUE (slug)
);

CREATE TABLE workspace_memberships (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    role         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspace_memberships_role_valid CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT workspace_memberships_workspace_user_unique UNIQUE (workspace_id, user_id)
);

CREATE INDEX workspace_memberships_user_idx ON workspace_memberships (user_id);
CREATE INDEX workspace_memberships_workspace_idx ON workspace_memberships (workspace_id);
CREATE UNIQUE INDEX app_users_email_unique_ci_idx ON app_users (lower(email));
