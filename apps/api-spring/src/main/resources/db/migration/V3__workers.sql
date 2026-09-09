CREATE TABLE worker_credentials (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    secret_hash   TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT worker_credentials_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT worker_credentials_secret_hash_not_blank CHECK (btrim(secret_hash) <> '')
);

CREATE TABLE workers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    credential_id       UUID NOT NULL REFERENCES worker_credentials(id) ON DELETE RESTRICT,
    name                TEXT NOT NULL,
    machine_identifier  TEXT NOT NULL,
    operating_system    TEXT NOT NULL,
    architecture        TEXT NOT NULL,
    cpu_model           TEXT NOT NULL,
    cpu_logical_cores   INTEGER NOT NULL,
    total_memory_bytes  BIGINT NOT NULL,
    gpu_model           TEXT,
    gpu_memory_bytes    BIGINT,
    agent_version       TEXT NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workers_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT workers_machine_identifier_not_blank CHECK (btrim(machine_identifier) <> ''),
    CONSTRAINT workers_operating_system_not_blank CHECK (btrim(operating_system) <> ''),
    CONSTRAINT workers_architecture_not_blank CHECK (btrim(architecture) <> ''),
    CONSTRAINT workers_cpu_model_not_blank CHECK (btrim(cpu_model) <> ''),
    CONSTRAINT workers_agent_version_not_blank CHECK (btrim(agent_version) <> ''),
    CONSTRAINT workers_cpu_logical_cores_positive CHECK (cpu_logical_cores > 0),
    CONSTRAINT workers_total_memory_bytes_positive CHECK (total_memory_bytes > 0),
    CONSTRAINT workers_gpu_memory_bytes_positive CHECK (gpu_memory_bytes IS NULL OR gpu_memory_bytes > 0),
    CONSTRAINT workers_workspace_machine_identifier_unique UNIQUE (workspace_id, machine_identifier)
);

CREATE INDEX worker_credentials_workspace_idx ON worker_credentials (workspace_id);
CREATE INDEX workers_workspace_idx ON workers (workspace_id);
CREATE INDEX workers_credential_idx ON workers (credential_id);
CREATE INDEX workers_last_seen_at_idx ON workers (last_seen_at);
