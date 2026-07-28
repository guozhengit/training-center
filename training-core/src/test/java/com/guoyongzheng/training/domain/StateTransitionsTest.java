package com.guoyongzheng.training.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StateTransitionsTest {

    @Test
    void acceptsEveryLegalSessionTransitionAndRejectsAllOthers() {
        Set<String> legal = Set.of(
                "CREATED->RUNNING",
                "RUNNING->PAUSED",
                "RUNNING->COMPLETED",
                "RUNNING->ABORTED",
                "PAUSED->RUNNING");

        assertEveryPair(
                SessionStatus.values(),
                legal,
                StateTransitions::requireLegal,
                StateTransitions::canTransition);
    }

    @Test
    void acceptsEveryLegalAttemptTransitionAndRejectsAllOthers() {
        Set<String> legal = Set.of(
                "CREATED->IN_PROGRESS",
                "IN_PROGRESS->SUBMITTED",
                "IN_PROGRESS->SKIPPED",
                "SUBMITTED->FINISHED");

        assertEveryPair(
                AttemptStatus.values(),
                legal,
                StateTransitions::requireLegal,
                StateTransitions::canTransition);
    }

    @Test
    void acceptsEveryLegalJudgementTransitionAndRejectsAllOthers() {
        Set<String> legal = Set.of(
                "QUEUED->RUNNING",
                "RUNNING->PASSED",
                "RUNNING->FAILED",
                "RUNNING->TIMED_OUT",
                "RUNNING->ENVIRONMENT_ERROR");

        assertEveryPair(
                JudgementStatus.values(),
                legal,
                StateTransitions::requireLegal,
                StateTransitions::canTransition);
    }

    private static <T extends Enum<T>> void assertEveryPair(
            T[] values,
            Set<String> legal,
            Transition<T> transition,
            CanTransition<T> canTransition) {
        for (T from : values) {
            for (T to : values) {
                String edge = from.name() + "->" + to.name();
                if (legal.contains(edge)) {
                    transition.apply(from, to);
                    assertThat(canTransition.test(from, to))
                            .as(edge)
                            .isTrue();
                } else {
                    assertThatIllegalStateException()
                            .as(edge)
                            .isThrownBy(() -> transition.apply(from, to))
                            .withMessageContaining(edge);
                    assertThat(canTransition.test(from, to))
                            .as(edge)
                            .isFalse();
                }
            }
        }
    }

    @FunctionalInterface
    private interface Transition<T> {
        void apply(T from, T to);
    }

    @FunctionalInterface
    private interface CanTransition<T> {
        boolean test(T from, T to);
    }
}
