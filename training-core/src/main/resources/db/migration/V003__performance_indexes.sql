-- Performance indexes for common query patterns on attempts and sessions.

-- Attempts: speed up history listing ordered by submission/start time
CREATE INDEX IF NOT EXISTS attempts_submitted_at_idx ON attempts(submitted_at);
CREATE INDEX IF NOT EXISTS attempts_started_at_idx ON attempts(started_at);

-- Attempts: speed up stats queries filtering by status and verdict
CREATE INDEX IF NOT EXISTS attempts_status_idx ON attempts(status);
CREATE INDEX IF NOT EXISTS attempts_verdict_idx ON attempts(verdict);

-- Sessions: speed up queries filtering by status or ordering by start time
CREATE INDEX IF NOT EXISTS sessions_status_idx ON sessions(status);
CREATE INDEX IF NOT EXISTS sessions_started_at_idx ON sessions(started_at);

-- Questions: speed up catalog filtering by track
CREATE INDEX IF NOT EXISTS questions_track_idx ON questions(track);
