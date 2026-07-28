CREATE TABLE should_rollback_parent (
    id TEXT PRIMARY KEY
);

CREATE TABLE should_rollback_child (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES should_rollback_parent(id)
);

INSERT INTO fixture_missing_table (id) VALUES ('force-rollback');
