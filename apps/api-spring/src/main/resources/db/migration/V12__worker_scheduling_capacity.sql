ALTER TABLE workers
    ADD COLUMN max_active_jobs INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT workers_max_active_jobs_valid CHECK (max_active_jobs >= 1 AND max_active_jobs <= 10000);
