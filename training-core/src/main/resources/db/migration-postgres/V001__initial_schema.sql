-- PostgreSQL initial schema for training-center.
--
-- Same tables and data invariants as the SQLite schema (V001__initial_schema.sql).
-- Differences by design:
--   * SQLite PRAGMA connection-policy checks are replaced by HikariCP configuration.
--   * SQLite triggers (RAISE(ABORT) state-machine/immutability guards) are enforced
--     by the application layer (StateTransitions, repository invariants) here;
--     column CHECK constraints, foreign keys, and unique constraints are preserved.
--   * Timestamps stay TEXT to match the ISO-8601 strings written by the application.

CREATE TABLE questions (
    id TEXT PRIMARY KEY,
    track TEXT NOT NULL CHECK (track IN ('CODING', 'ORAL', 'PROJECT')),
    group_name TEXT NOT NULL,
    title TEXT NOT NULL,
    topic TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    language TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    starter_ref TEXT,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    mode TEXT NOT NULL,
    seed BIGINT NOT NULL,
    started_at TEXT NOT NULL,
    deadline_at TEXT,
    finished_at TEXT,
    status TEXT NOT NULL CHECK (
        status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'ABORTED')
    ),
    config_json TEXT NOT NULL
);

CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    question_id TEXT NOT NULL REFERENCES questions(id),
    started_at TEXT NOT NULL,
    submitted_at TEXT,
    duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    status TEXT NOT NULL CHECK (
        status IN ('CREATED', 'IN_PROGRESS', 'SUBMITTED', 'FINISHED', 'SKIPPED')
    ),
    verdict TEXT,
    answer_unlocked INTEGER NOT NULL DEFAULT 0 CHECK (answer_unlocked IN (0, 1)),
    sandbox_path TEXT,
    notes TEXT,
    improved_answer TEXT
);

CREATE INDEX attempts_session_idx ON attempts(session_id);
CREATE INDEX attempts_question_idx ON attempts(question_id);

CREATE TABLE judgements (
    id TEXT PRIMARY KEY,
    attempt_id TEXT NOT NULL REFERENCES attempts(id),
    sequence_no INTEGER NOT NULL CHECK (sequence_no > 0),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    status TEXT NOT NULL CHECK (
        status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'TIMED_OUT', 'ENVIRONMENT_ERROR')
    ),
    exit_code INTEGER,
    passed_count INTEGER CHECK (passed_count IS NULL OR passed_count >= 0),
    failed_count INTEGER CHECK (failed_count IS NULL OR failed_count >= 0),
    duration_millis INTEGER CHECK (duration_millis IS NULL OR duration_millis >= 0),
    stdout_excerpt TEXT,
    stderr_excerpt TEXT,
    UNIQUE (attempt_id, sequence_no)
);

CREATE INDEX judgements_attempt_idx ON judgements(attempt_id, sequence_no);

CREATE TABLE oral_scores (
    attempt_id TEXT PRIMARY KEY REFERENCES attempts(id),
    correctness INTEGER NOT NULL CHECK (correctness BETWEEN 0 AND 2),
    structure INTEGER NOT NULL CHECK (structure BETWEEN 0 AND 2),
    project_evidence INTEGER NOT NULL CHECK (project_evidence BETWEEN 0 AND 2),
    tradeoff INTEGER NOT NULL CHECK (tradeoff BETWEEN 0 AND 2),
    fact_restraint INTEGER NOT NULL CHECK (fact_restraint BETWEEN 0 AND 2)
);

CREATE TABLE review_queue (
    question_id TEXT PRIMARY KEY REFERENCES questions(id),
    wrong_count INTEGER NOT NULL CHECK (wrong_count >= 0),
    review_count INTEGER NOT NULL CHECK (review_count >= 0),
    last_result TEXT,
    last_attempt_at TEXT,
    next_review_at TEXT NOT NULL,
    interval_days INTEGER NOT NULL CHECK (interval_days IN (1, 3, 7, 14))
);

CREATE INDEX review_queue_due_idx ON review_queue(next_review_at);

CREATE TABLE plan_days (
    id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    day_number INTEGER NOT NULL CHECK (day_number BETWEEN 1 AND 14),
    plan_date TEXT NOT NULL,
    theme TEXT NOT NULL,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    UNIQUE (plan_id, day_number)
);

CREATE TABLE plan_tasks (
    id TEXT PRIMARY KEY,
    plan_day_id TEXT NOT NULL REFERENCES plan_days(id),
    task_order INTEGER NOT NULL CHECK (task_order > 0),
    task_type TEXT NOT NULL,
    title TEXT NOT NULL,
    target_count INTEGER NOT NULL CHECK (target_count >= 0),
    completed_count INTEGER NOT NULL DEFAULT 0 CHECK (
        completed_count >= 0 AND completed_count <= target_count
    ),
    completed_at TEXT,
    UNIQUE (plan_day_id, task_order)
);

CREATE INDEX plan_tasks_day_idx ON plan_tasks(plan_day_id, task_order);
