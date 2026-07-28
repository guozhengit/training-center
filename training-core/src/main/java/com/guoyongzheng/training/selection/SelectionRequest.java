package com.guoyongzheng.training.selection;

import com.guoyongzheng.training.catalog.QuestionFilter;

import java.util.Objects;
import java.util.Set;

/** Immutable input for a single deterministic question-selection operation. */
public record SelectionRequest(
        QuestionFilter filter,
        int count,
        long seed,
        boolean prioritizeWrongAnswers,
        boolean dueReviewOnly,
        Set<String> sessionQuestionIds) {

    public SelectionRequest {
        filter = Objects.requireNonNull(filter, "filter");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        sessionQuestionIds = Set.copyOf(Objects.requireNonNull(sessionQuestionIds, "sessionQuestionIds"));
    }
}
