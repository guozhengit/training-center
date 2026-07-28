CREATE TEMP TABLE migration_connection_policy (
    foreign_keys INTEGER
        CONSTRAINT migration_connection_policy_foreign_keys CHECK (foreign_keys = 1),
    journal_mode TEXT
        CONSTRAINT migration_connection_policy_journal_mode CHECK (lower(journal_mode) = 'wal'),
    busy_timeout INTEGER
        CONSTRAINT migration_connection_policy_busy_timeout CHECK (busy_timeout >= 5000)
);

INSERT INTO migration_connection_policy (foreign_keys, journal_mode, busy_timeout)
SELECT
    (SELECT foreign_keys FROM pragma_foreign_keys),
    (SELECT journal_mode FROM pragma_journal_mode),
    (SELECT timeout FROM pragma_busy_timeout);

DROP TABLE migration_connection_policy;

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
    seed INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    deadline_at TEXT,
    finished_at TEXT,
    status TEXT NOT NULL CHECK (
        status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'ABORTED')
    ),
    config_json TEXT NOT NULL
);

CREATE TRIGGER sessions_initial_state
BEFORE INSERT ON sessions
WHEN NEW.status <> 'CREATED'
BEGIN
    SELECT RAISE(ABORT, 'session initial state must be CREATED');
END;

CREATE TRIGGER sessions_legal_transition
BEFORE UPDATE OF status ON sessions
WHEN NOT (
    (OLD.status = 'CREATED' AND NEW.status = 'RUNNING')
    OR (OLD.status = 'RUNNING' AND NEW.status IN ('PAUSED', 'COMPLETED', 'ABORTED'))
    OR (OLD.status = 'PAUSED' AND NEW.status = 'RUNNING')
)
BEGIN
    SELECT RAISE(ABORT, 'illegal session state transition');
END;

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

CREATE TRIGGER attempts_initial_state
BEFORE INSERT ON attempts
WHEN NEW.status <> 'CREATED'
BEGIN
    SELECT RAISE(ABORT, 'attempt initial state must be CREATED');
END;

CREATE TRIGGER attempts_legal_transition
BEFORE UPDATE OF status ON attempts
WHEN NOT (
    (OLD.status = 'CREATED' AND NEW.status = 'IN_PROGRESS')
    OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('SUBMITTED', 'SKIPPED'))
    OR (OLD.status = 'SUBMITTED' AND NEW.status = 'FINISHED')
)
BEGIN
    SELECT RAISE(ABORT, 'illegal attempt state transition');
END;

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

CREATE TRIGGER judgements_existing_id_conflict
BEFORE INSERT ON judgements
WHEN EXISTS (SELECT 1 FROM judgements WHERE id = NEW.id)
BEGIN
    SELECT RAISE(ABORT, 'judgement id already exists');
END;

CREATE TRIGGER judgements_existing_sequence_conflict
BEFORE INSERT ON judgements
WHEN EXISTS (
    SELECT 1 FROM judgements
    WHERE attempt_id = NEW.attempt_id AND sequence_no = NEW.sequence_no
)
BEGIN
    SELECT RAISE(ABORT, 'judgement sequence already exists');
END;

CREATE TRIGGER judgements_initial_state
BEFORE INSERT ON judgements
WHEN NEW.status <> 'QUEUED'
BEGIN
    SELECT RAISE(ABORT, 'judgement initial state must be QUEUED');
END;

CREATE TRIGGER judgements_legal_transition
BEFORE UPDATE OF status ON judgements
WHEN NOT (
    (OLD.status = 'QUEUED' AND NEW.status = 'RUNNING')
    OR (OLD.status = 'RUNNING'
        AND NEW.status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'ENVIRONMENT_ERROR'))
)
BEGIN
    SELECT RAISE(ABORT, 'illegal judgement state transition');
END;

CREATE TRIGGER judgements_identity_immutable
BEFORE UPDATE OF id, attempt_id, sequence_no ON judgements
BEGIN
    SELECT RAISE(ABORT, 'judgement identity is immutable');
END;

CREATE TRIGGER judgements_append_only
BEFORE DELETE ON judgements
BEGIN
    SELECT RAISE(ABORT, 'judgements are append-only');
END;

CREATE TRIGGER judgements_result_fields_on_terminal_transition
BEFORE UPDATE OF
    finished_at, exit_code, passed_count, failed_count, duration_millis,
    stdout_excerpt, stderr_excerpt
ON judgements
WHEN OLD.status NOT IN ('PASSED', 'FAILED', 'TIMED_OUT', 'ENVIRONMENT_ERROR')
AND NOT (
    (OLD.status = 'RUNNING'
        AND NEW.status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'ENVIRONMENT_ERROR'))
    OR (
        NEW.finished_at IS OLD.finished_at
        AND NEW.exit_code IS OLD.exit_code
        AND NEW.passed_count IS OLD.passed_count
        AND NEW.failed_count IS OLD.failed_count
        AND NEW.duration_millis IS OLD.duration_millis
        AND NEW.stdout_excerpt IS OLD.stdout_excerpt
        AND NEW.stderr_excerpt IS OLD.stderr_excerpt
    )
)
BEGIN
    SELECT RAISE(ABORT, 'judgement result fields require terminal transition');
END;

CREATE TRIGGER judgements_terminal_immutable
BEFORE UPDATE ON judgements
WHEN OLD.status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'ENVIRONMENT_ERROR')
BEGIN
    SELECT RAISE(ABORT, 'terminal judgement is immutable');
END;

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
