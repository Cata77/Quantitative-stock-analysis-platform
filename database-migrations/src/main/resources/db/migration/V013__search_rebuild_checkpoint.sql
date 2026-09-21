-- Search is a replaceable projection. A checkpoint is committed only after an atomic alias swap.
CREATE TABLE operations.search_rebuild_checkpoints (
    alias_name TEXT PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    index_name TEXT NOT NULL,
    content_sha256 TEXT NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    document_count INTEGER NOT NULL CHECK (document_count >= 0),
    source_as_of TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
