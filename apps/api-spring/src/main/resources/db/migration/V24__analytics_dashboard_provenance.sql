ALTER TABLE publication_attributions ADD COLUMN content_source_name_snapshot TEXT;

-- V23 stored only the source ID. Older names reflect the value at migration
-- time, not a provable publication-time name.
UPDATE publication_attributions a
SET content_source_name_snapshot = s.name
FROM content_sources s
WHERE a.content_source_id = s.id AND a.workspace_id = s.workspace_id;

CREATE INDEX publications_workspace_published_idx
    ON publications(workspace_id, published_at, id)
    WHERE status = 'PUBLISHED';
