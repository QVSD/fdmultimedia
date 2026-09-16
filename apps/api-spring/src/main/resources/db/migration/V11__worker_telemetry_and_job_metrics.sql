ALTER TABLE workers
    ADD COLUMN current_supported_job_types JSONB,
    ADD COLUMN current_supported_highlight_analyzers JSONB,
    ADD COLUMN system_cpu_load DOUBLE PRECISION,
    ADD COLUMN process_cpu_load DOUBLE PRECISION,
    ADD COLUMN available_memory_bytes BIGINT,
    ADD COLUMN jvm_heap_used_bytes BIGINT,
    ADD COLUMN jvm_heap_max_bytes BIGINT,
    ADD COLUMN active_jobs INTEGER,
    ADD COLUMN last_telemetry_at TIMESTAMPTZ,
    ADD CONSTRAINT workers_system_cpu_load_valid CHECK (system_cpu_load IS NULL OR (system_cpu_load >= 0 AND system_cpu_load <= 1)),
    ADD CONSTRAINT workers_process_cpu_load_valid CHECK (process_cpu_load IS NULL OR (process_cpu_load >= 0 AND process_cpu_load <= 1)),
    ADD CONSTRAINT workers_available_memory_bytes_valid CHECK (available_memory_bytes IS NULL OR available_memory_bytes >= 0),
    ADD CONSTRAINT workers_jvm_heap_used_bytes_valid CHECK (jvm_heap_used_bytes IS NULL OR jvm_heap_used_bytes >= 0),
    ADD CONSTRAINT workers_jvm_heap_max_bytes_valid CHECK (jvm_heap_max_bytes IS NULL OR jvm_heap_max_bytes >= 0),
    ADD CONSTRAINT workers_active_jobs_valid CHECK (active_jobs IS NULL OR active_jobs >= 0);

CREATE TABLE job_execution_metrics (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                   UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    workspace_id             UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    worker_id                UUID REFERENCES workers(id) ON DELETE SET NULL,
    job_type                 TEXT NOT NULL,
    attempt                  INTEGER NOT NULL,
    outcome                  TEXT NOT NULL,
    queue_wait_ms            BIGINT,
    execution_ms             BIGINT,
    total_latency_ms         BIGINT,
    workload_size_bytes      BIGINT,
    workload_duration_ms     BIGINT,
    workload_width           INTEGER,
    workload_height          INTEGER,
    provider                 TEXT,
    model                    TEXT,
    analyzer_type            TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_execution_metrics_attempt_positive CHECK (attempt > 0),
    CONSTRAINT job_execution_metrics_queue_wait_non_negative CHECK (queue_wait_ms IS NULL OR queue_wait_ms >= 0),
    CONSTRAINT job_execution_metrics_execution_non_negative CHECK (execution_ms IS NULL OR execution_ms >= 0),
    CONSTRAINT job_execution_metrics_total_latency_non_negative CHECK (total_latency_ms IS NULL OR total_latency_ms >= 0),
    CONSTRAINT job_execution_metrics_workload_size_non_negative CHECK (workload_size_bytes IS NULL OR workload_size_bytes >= 0),
    CONSTRAINT job_execution_metrics_workload_duration_non_negative CHECK (workload_duration_ms IS NULL OR workload_duration_ms >= 0),
    CONSTRAINT job_execution_metrics_workload_width_positive CHECK (workload_width IS NULL OR workload_width > 0),
    CONSTRAINT job_execution_metrics_workload_height_positive CHECK (workload_height IS NULL OR workload_height > 0),
    CONSTRAINT job_execution_metrics_job_attempt_unique UNIQUE (job_id, attempt)
);

CREATE INDEX job_execution_metrics_workspace_created_idx ON job_execution_metrics (workspace_id, created_at DESC);
CREATE INDEX job_execution_metrics_worker_job_type_idx ON job_execution_metrics (worker_id, job_type, created_at DESC);
CREATE INDEX job_execution_metrics_job_type_outcome_idx ON job_execution_metrics (job_type, outcome, created_at DESC);
