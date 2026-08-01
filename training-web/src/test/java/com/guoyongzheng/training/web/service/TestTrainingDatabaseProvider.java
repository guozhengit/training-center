package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.persistence.TrainingDatabase;

/** Test double that serves a caller-managed temporary database instead of the real workspace. */
final class TestTrainingDatabaseProvider extends TrainingDatabaseProvider {
    private final TrainingDatabase database;

    TestTrainingDatabaseProvider(TrainingDatabase database) {
        super(null, null);
        this.database = database;
    }

    @Override
    public synchronized TrainingDatabase readyDatabase() {
        return database;
    }
}
