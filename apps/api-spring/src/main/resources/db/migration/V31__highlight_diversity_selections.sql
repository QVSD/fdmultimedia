CREATE TABLE highlight_selections (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    media_asset_id uuid NOT NULL REFERENCES media_assets(id),
    highlight_analysis_id uuid NOT NULL REFERENCES highlight_analyses(id),
    selector_version varchar(64) NOT NULL,
    requested_count integer NOT NULL,
    selected_count integer NOT NULL,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT highlight_selections_requested_count_valid CHECK (requested_count BETWEEN 1 AND 5),
    CONSTRAINT highlight_selections_selected_count_valid CHECK (selected_count BETWEEN 0 AND requested_count),
    CONSTRAINT highlight_selections_status_valid CHECK (status IN ('COMPLETE', 'PARTIAL', 'EMPTY'))
);

CREATE TABLE highlight_selection_items (
    id uuid PRIMARY KEY,
    selection_id uuid NOT NULL REFERENCES highlight_selections(id) ON DELETE CASCADE,
    highlight_candidate_id uuid NOT NULL REFERENCES highlight_candidates(id),
    selection_order integer NOT NULL,
    source_rank integer NOT NULL,
    clip_asset_id uuid REFERENCES media_assets(id),
    clip_job_id uuid REFERENCES jobs(id),
    clip_request_failure_code varchar(100),
    clip_request_failure_message varchar(500),
    CONSTRAINT highlight_selection_items_order_valid CHECK (selection_order > 0),
    CONSTRAINT highlight_selection_items_source_rank_valid CHECK (source_rank > 0),
    CONSTRAINT highlight_selection_items_candidate_unique UNIQUE (selection_id, highlight_candidate_id),
    CONSTRAINT highlight_selection_items_order_unique UNIQUE (selection_id, selection_order)
);

CREATE TABLE highlight_selection_exclusions (
    id uuid PRIMARY KEY,
    selection_id uuid NOT NULL REFERENCES highlight_selections(id) ON DELETE CASCADE,
    highlight_candidate_id uuid NOT NULL REFERENCES highlight_candidates(id),
    reason varchar(64) NOT NULL,
    conflicting_candidate_id uuid REFERENCES highlight_candidates(id),
    temporal_overlap_ratio numeric(5,4),
    lexical_similarity numeric(5,4),
    CONSTRAINT highlight_selection_exclusions_candidate_unique UNIQUE (selection_id, highlight_candidate_id),
    CONSTRAINT highlight_selection_exclusions_reason_valid CHECK (reason IN (
        'TEMPORAL_OVERLAP', 'INSUFFICIENT_TEMPORAL_GAP', 'LEXICAL_DUPLICATE',
        'BELOW_QUALITY_FLOOR', 'SELECTION_LIMIT_REACHED'
    )),
    CONSTRAINT highlight_selection_exclusions_temporal_valid CHECK (
        temporal_overlap_ratio IS NULL OR (temporal_overlap_ratio >= 0 AND temporal_overlap_ratio <= 1)
    ),
    CONSTRAINT highlight_selection_exclusions_lexical_valid CHECK (
        lexical_similarity IS NULL OR (lexical_similarity >= 0 AND lexical_similarity <= 1)
    )
);

CREATE INDEX idx_highlight_selections_workspace_asset_created
    ON highlight_selections(workspace_id, media_asset_id, created_at DESC);
CREATE INDEX idx_highlight_selections_analysis_created
    ON highlight_selections(highlight_analysis_id, created_at DESC);
CREATE INDEX idx_highlight_selection_items_selection_order
    ON highlight_selection_items(selection_id, selection_order);
CREATE INDEX idx_highlight_selection_exclusions_selection
    ON highlight_selection_exclusions(selection_id);
