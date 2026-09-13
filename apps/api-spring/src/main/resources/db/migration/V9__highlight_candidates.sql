ALTER TABLE jobs DROP CONSTRAINT jobs_type_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_type_valid CHECK (type IN (
    'SYSTEM_TEST',
    'IMPORT_MEDIA',
    'INSPECT_MEDIA',
    'CREATE_CLIP',
    'CREATE_SOCIAL_VERTICAL',
    'ANALYZE_HIGHLIGHTS'
));

CREATE TABLE highlight_analyses (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    asset_id uuid NOT NULL REFERENCES media_assets(id),
    status varchar(32) NOT NULL,
    analysis_job_id uuid NOT NULL REFERENCES jobs(id),
    analyzer_type varchar(64) NOT NULL,
    analyzer_version varchar(64) NOT NULL,
    error_code varchar(100),
    error_message varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT highlight_analyses_status_valid CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE highlight_candidates (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    analysis_id uuid NOT NULL REFERENCES highlight_analyses(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL REFERENCES media_assets(id),
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    score numeric(5,4) NOT NULL,
    reason varchar(500) NOT NULL,
    rank integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT highlight_candidates_interval_valid CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT highlight_candidates_score_valid CHECK (score >= 0 AND score <= 1),
    CONSTRAINT highlight_candidates_rank_valid CHECK (rank > 0),
    CONSTRAINT highlight_candidates_rank_unique UNIQUE (analysis_id, rank)
);

CREATE INDEX idx_highlight_analyses_workspace_asset_created
    ON highlight_analyses(workspace_id, asset_id, created_at DESC);

CREATE INDEX idx_highlight_analyses_workspace_status
    ON highlight_analyses(workspace_id, status);

CREATE INDEX idx_highlight_analyses_job
    ON highlight_analyses(analysis_job_id);

CREATE INDEX idx_highlight_candidates_workspace_asset
    ON highlight_candidates(workspace_id, asset_id);

CREATE INDEX idx_highlight_candidates_analysis_rank
    ON highlight_candidates(analysis_id, rank);
