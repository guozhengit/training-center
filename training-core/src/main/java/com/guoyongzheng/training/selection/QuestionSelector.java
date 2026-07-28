package com.guoyongzheng.training.selection;

import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.persistence.ReviewRepository.ReviewEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.random.RandomGenerator;

/** Deterministically selects questions with weighted sampling and no replacement. */
public final class QuestionSelector {
    private static final int DUE_WRONG_WEIGHT = 8;
    private static final int RECENT_WRONG_WEIGHT = 5;
    private static final int UNSEEN_WEIGHT = 3;
    private static final int RECENT_PASS_WEIGHT = 1;

    private final Clock clock;

    public QuestionSelector(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<QuestionDescriptor> select(SelectionRequest request, List<QuestionDescriptor> questions,
                                           Map<String, ReviewEntry> reviews) {
        Objects.requireNonNull(request, "request");
        return select(request, questions, reviews, new Random(request.seed()));
    }

    List<QuestionDescriptor> select(SelectionRequest request, List<QuestionDescriptor> questions,
                                    Map<String, ReviewEntry> reviews, RandomGenerator random) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(questions, "questions");
        Objects.requireNonNull(reviews, "reviews");
        Objects.requireNonNull(random, "random");

        Instant now = clock.instant();
        List<Candidate> candidates = questions.stream()
                .filter(question -> request.filter().matches(question))
                .filter(question -> !request.sessionQuestionIds().contains(question.id()))
                .filter(question -> !request.dueReviewOnly() || isDueWrong(reviews.get(question.id()), now))
                .sorted(Comparator.comparing(QuestionDescriptor::id))
                .map(question -> new Candidate(question, candidateWeight(reviews.get(question.id()), now,
                        request.prioritizeWrongAnswers())))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        List<QuestionDescriptor> selected = new ArrayList<>(Math.min(request.count(), candidates.size()));
        while (!candidates.isEmpty() && selected.size() < request.count()) {
            long totalWeight = candidates.stream().mapToLong(Candidate::weight).sum();
            long draw = random.nextLong(totalWeight);
            int selectedIndex = findSelectedIndex(candidates, draw);
            selected.add(candidates.remove(selectedIndex).question());
        }
        return List.copyOf(selected);
    }

    private static int findSelectedIndex(List<Candidate> candidates, long draw) {
        long cumulative = 0;
        for (int index = 0; index < candidates.size(); index++) {
            cumulative += candidates.get(index).weight();
            if (draw < cumulative) {
                return index;
            }
        }
        throw new IllegalStateException("Random draw exceeded candidate weights");
    }

    static int candidateWeight(ReviewEntry review, Instant now, boolean prioritizeWrongAnswers) {
        if (!prioritizeWrongAnswers) {
            return RECENT_PASS_WEIGHT;
        }
        if (isUnseen(review)) {
            return UNSEEN_WEIGHT;
        }
        if (isFailed(review) && review.nextReviewAt() == null) {
            throw new IllegalArgumentException("Failed review history requires nextReviewAt");
        }
        if (isDueWrong(review, now)) {
            return DUE_WRONG_WEIGHT;
        }
        if (isRecentWrong(review, now)) {
            return RECENT_WRONG_WEIGHT;
        }
        return RECENT_PASS_WEIGHT;
    }

    private static boolean isDueWrong(ReviewEntry review, Instant now) {
        return isFailed(review)
                && review.nextReviewAt() != null
                && !review.nextReviewAt().isAfter(now);
    }

    private static boolean isRecentWrong(ReviewEntry review, Instant now) {
        return isFailed(review)
                && review.nextReviewAt() != null
                && review.nextReviewAt().isAfter(now);
    }

    private static boolean isUnseen(ReviewEntry review) {
        return review == null || (review.reviewCount() == 0
                && (review.lastResult() == null || review.lastResult().isBlank()));
    }

    private static boolean isFailed(ReviewEntry review) {
        return review != null && review.wrongCount() > 0 && "FAILED".equals(review.lastResult());
    }

    private record Candidate(QuestionDescriptor question, int weight) {
    }
}
