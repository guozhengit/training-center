package com.guoyongzheng.training.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Central guard for all persisted state-machine transitions. */
public final class StateTransitions {
    private static final Map<SessionStatus, Set<SessionStatus>> SESSION = Map.of(
            SessionStatus.CREATED, Set.of(SessionStatus.RUNNING),
            SessionStatus.RUNNING, Set.of(SessionStatus.PAUSED, SessionStatus.COMPLETED, SessionStatus.ABORTED),
            SessionStatus.PAUSED, Set.of(SessionStatus.RUNNING));
    private static final Map<AttemptStatus, Set<AttemptStatus>> ATTEMPT = Map.of(
            AttemptStatus.CREATED, Set.of(AttemptStatus.IN_PROGRESS),
            AttemptStatus.IN_PROGRESS, Set.of(AttemptStatus.SUBMITTED, AttemptStatus.SKIPPED),
            AttemptStatus.SUBMITTED, Set.of(AttemptStatus.FINISHED));
    private static final Map<JudgementStatus, Set<JudgementStatus>> JUDGEMENT = Map.of(
            JudgementStatus.QUEUED, Set.of(JudgementStatus.RUNNING),
            JudgementStatus.RUNNING, Set.of(
                    JudgementStatus.PASSED,
                    JudgementStatus.FAILED,
                    JudgementStatus.TIMED_OUT,
                    JudgementStatus.ENVIRONMENT_ERROR));

    private StateTransitions() {
    }

    public static boolean canTransition(SessionStatus from, SessionStatus to) {
        return canTransition(SESSION, from, to);
    }

    public static boolean canTransition(AttemptStatus from, AttemptStatus to) {
        return canTransition(ATTEMPT, from, to);
    }

    public static boolean canTransition(JudgementStatus from, JudgementStatus to) {
        return canTransition(JUDGEMENT, from, to);
    }

    public static void requireLegal(SessionStatus from, SessionStatus to) {
        requireLegal(SESSION, from, to);
    }

    public static void requireLegal(AttemptStatus from, AttemptStatus to) {
        requireLegal(ATTEMPT, from, to);
    }

    public static void requireLegal(JudgementStatus from, JudgementStatus to) {
        requireLegal(JUDGEMENT, from, to);
    }

    private static <T extends Enum<T>> boolean canTransition(Map<T, Set<T>> transitions, T from, T to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return transitions.getOrDefault(from, Set.of()).contains(to);
    }

    private static <T extends Enum<T>> void requireLegal(Map<T, Set<T>> transitions, T from, T to) {
        if (!canTransition(transitions, from, to)) {
            throw new IllegalStateException("Illegal state transition: " + from.name() + "->" + to.name());
        }
    }
}
