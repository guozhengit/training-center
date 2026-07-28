package com.guoyongzheng.training.selection;

import com.guoyongzheng.training.catalog.QuestionFilter;
import com.guoyongzheng.training.domain.QuestionDescriptor;
import com.guoyongzheng.training.domain.Track;
import com.guoyongzheng.training.persistence.ReviewRepository.ReviewEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class QuestionSelectorTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void appliesFiltersStrictlyWithoutBackfillingOutsideScope() {
        SelectionRequest request = request(new QuestionFilter(Track.ORAL, null, "java", null, null), 3, 42L, Set.of());

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(
                request,
                List.of(question("B001", Track.ORAL, "java"), question("B002", Track.ORAL, "spring"),
                        question("B003", Track.CODING, "java")),
                Map.of());

        assertThat(selected).extracting(QuestionDescriptor::id).containsExactly("B001");
    }

    @Test
    void excludesQuestionsAlreadyPresentInTheSession() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 2, 42L, Set.of("B001"));

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(
                request, List.of(question("B001"), question("B002"), question("B003")), Map.of());

        assertThat(selected).extracting(QuestionDescriptor::id).doesNotContain("B001").hasSize(2);
    }

    @Test
    void returnsOnlyAvailableCandidatesWhenTheRequestExceedsThem() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 4, 42L, Set.of());

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(
                request, List.of(question("B001"), question("B002")), Map.of());

        assertThat(selected).extracting(QuestionDescriptor::id).containsExactlyInAnyOrder("B001", "B002");
    }

    @Test
    void replaysTheSameOrderForTheSameSeedRegardlessOfInputOrder() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 4, 725L, Set.of());
        List<QuestionDescriptor> questions = List.of(question("B004"), question("B002"), question("B003"), question("B001"));

        List<String> first = new QuestionSelector(CLOCK).select(request, questions, Map.of()).stream()
                .map(QuestionDescriptor::id).toList();
        List<QuestionDescriptor> reverseOrder = List.of(question("B001"), question("B003"), question("B002"), question("B004"));
        List<String> second = new QuestionSelector(CLOCK).select(request, reverseOrder, Map.of()).stream()
                .map(QuestionDescriptor::id).toList();

        assertThat(first).containsExactlyElementsOf(second);
    }

    @Test
    void variesTheOrderForDifferentSeeds() {
        List<QuestionDescriptor> questions = List.of(question("B001"), question("B002"), question("B003"),
                question("B004"), question("B005"), question("B006"));

        List<String> first = new QuestionSelector(CLOCK).select(request(new QuestionFilter(null, null, null, null, null), 6, 1L, Set.of()), questions, Map.of())
                .stream().map(QuestionDescriptor::id).toList();
        List<String> second = new QuestionSelector(CLOCK).select(request(new QuestionFilter(null, null, null, null, null), 6, 2L, Set.of()), questions, Map.of())
                .stream().map(QuestionDescriptor::id).toList();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void usesTheHighestApplicableWeightAndTreatsTheClockBoundaryAsDue() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 1, 9L, Set.of());
        QuestionDescriptor recentPass = question("A001");
        QuestionDescriptor dueWrong = question("B001");
        QuestionDescriptor recentWrong = question("C001");
        Map<String, ReviewEntry> reviews = Map.of(
                "A001", review("A001", 2, "PASSED", NOW.plusSeconds(3600)),
                "B001", review("B001", 4, "FAILED", NOW),
                "C001", review("C001", 3, "FAILED", NOW.plusSeconds(3600)));
        RandomGenerator drawSix = () -> 12L;

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(
                request, List.of(recentWrong, recentPass, dueWrong), reviews, drawSix);

        assertThat(selected).extracting(QuestionDescriptor::id).containsExactly("B001");
    }

    @Test
    void classifiesNullAndEmptyHistoryAsUnseenWithWeightThree() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 1, 9L, Set.of());
        QuestionDescriptor recentPass = question("A001");
        QuestionDescriptor nullHistory = question("B001");
        QuestionDescriptor emptyHistory = question("C001");
        QuestionDescriptor recentWrong = question("D001");
        Map<String, ReviewEntry> nullHistoryReviews = Map.of(
                "A001", review("A001", 0, "PASSED", NOW.plusSeconds(3600)),
                "D001", review("D001", 1, "FAILED", NOW.plusSeconds(3600)));
        Map<String, ReviewEntry> emptyHistoryReviews = Map.of(
                "A001", review("A001", 0, "PASSED", NOW.plusSeconds(3600)),
                "C001", new ReviewEntry("C001", 0, 0, null, null, null, 1),
                "D001", review("D001", 1, "FAILED", NOW.plusSeconds(3600)));

        List<QuestionDescriptor> nullHistorySelected = new QuestionSelector(CLOCK).select(request,
                List.of(recentPass, nullHistory, recentWrong), nullHistoryReviews, new ScriptedRandom(2));
        List<QuestionDescriptor> emptyHistorySelected = new QuestionSelector(CLOCK).select(request,
                List.of(recentPass, emptyHistory, recentWrong), emptyHistoryReviews, new ScriptedRandom(2));

        assertThat(nullHistorySelected).extracting(QuestionDescriptor::id).containsExactly("B001");
        assertThat(emptyHistorySelected).extracting(QuestionDescriptor::id).containsExactly("C001");
    }

    @Test
    void rejectsFailedHistoryWithoutANextReviewDate() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 1, 9L, Set.of());
        ReviewEntry invalid = new ReviewEntry("B001", 1, 1, "FAILED", NOW.minusSeconds(60), null, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> new QuestionSelector(CLOCK).select(
                request, List.of(question("B001")), Map.of("B001", invalid)));
    }

    @Test
    void usesUniformPublicSelectionWhenWrongAnswerPrioritizationIsDisabled() {
        SelectionRequest uniformRequest = new SelectionRequest(new QuestionFilter(null, null, null, null, null),
                4, 725L, false, false, Set.of());
        List<QuestionDescriptor> questions = List.of(question("A001"), question("B001"), question("C001"), question("D001"));
        Map<String, ReviewEntry> histories = Map.of(
                "A001", review("A001", 1, "FAILED", NOW.minusSeconds(1)),
                "B001", review("B001", 1, "FAILED", NOW.plusSeconds(1)),
                "C001", new ReviewEntry("C001", 0, 0, null, null, null, 1),
                "D001", review("D001", 0, "PASSED", NOW.plusSeconds(1)));

        List<String> withHistory = new QuestionSelector(CLOCK).select(uniformRequest, questions, histories).stream()
                .map(QuestionDescriptor::id).toList();
        List<String> withoutHistory = new QuestionSelector(CLOCK).select(uniformRequest, questions, Map.of()).stream()
                .map(QuestionDescriptor::id).toList();

        assertThat(withHistory).containsExactlyElementsOf(withoutHistory);
    }

    @Test
    void dueOnlyPublicSelectionKeepsOnlyFailedQuestionsDueBeforeOrAtNow() {
        SelectionRequest dueOnly = new SelectionRequest(new QuestionFilter(null, null, null, null, null),
                6, 725L, true, true, Set.of());
        List<QuestionDescriptor> questions = List.of(question("A001"), question("B001"), question("C001"),
                question("D001"), question("E001"), question("F001"));
        Map<String, ReviewEntry> histories = Map.of(
                "A001", review("A001", 1, "FAILED", NOW.minusSeconds(1)),
                "B001", review("B001", 1, "FAILED", NOW),
                "C001", review("C001", 1, "FAILED", NOW.plusSeconds(1)),
                "D001", new ReviewEntry("D001", 1, 1, "FAILED", NOW.minusSeconds(60), null, 1),
                "F001", review("F001", 0, "PASSED", NOW.minusSeconds(1)));

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(dueOnly, questions, histories);

        assertThat(selected).extracting(QuestionDescriptor::id).containsExactlyInAnyOrder("A001", "B001");
    }

    @Test
    void publicPrioritizedSelectionAcceptsDueRecentUnseenAndPassedStates() {
        SelectionRequest request = request(new QuestionFilter(null, null, null, null, null), 4, 725L, Set.of());
        List<QuestionDescriptor> questions = List.of(question("A001"), question("B001"), question("C001"), question("D001"));
        Map<String, ReviewEntry> histories = Map.of(
                "A001", review("A001", 1, "FAILED", NOW),
                "B001", review("B001", 1, "FAILED", NOW.plusSeconds(1)),
                "D001", review("D001", 0, "PASSED", NOW.plusSeconds(1)));

        List<QuestionDescriptor> selected = new QuestionSelector(CLOCK).select(request, questions, histories);

        assertThat(selected).extracting(QuestionDescriptor::id).containsExactlyInAnyOrder("A001", "B001", "C001", "D001");
    }

    @Test
    void assignsExactWeightsWithDueStateTakingPrecedence() {
        ReviewEntry dueFailed = review("A001", 1, "FAILED", NOW);
        ReviewEntry futureFailed = review("B001", 1, "FAILED", NOW.plusSeconds(1));
        ReviewEntry passed = review("C001", 0, "PASSED", NOW.plusSeconds(1));

        assertThat(QuestionSelector.candidateWeight(dueFailed, NOW, true)).isEqualTo(8);
        assertThat(QuestionSelector.candidateWeight(futureFailed, NOW, true)).isEqualTo(5);
        assertThat(QuestionSelector.candidateWeight(null, NOW, true)).isEqualTo(3);
        assertThat(QuestionSelector.candidateWeight(passed, NOW, true)).isEqualTo(1);
        assertThat(QuestionSelector.candidateWeight(dueFailed, NOW, false)).isEqualTo(1);
    }

    private static SelectionRequest request(QuestionFilter filter, int count, long seed, Set<String> sessionQuestionIds) {
        return new SelectionRequest(filter, count, seed, true, false, sessionQuestionIds);
    }

    private static QuestionDescriptor question(String id) {
        return question(id, Track.CODING, "java");
    }

    private static QuestionDescriptor question(String id, Track track, String topic) {
        return new QuestionDescriptor(id, track, "group", id, topic, "easy", "java", "source", null);
    }

    private static ReviewEntry review(String questionId, int wrongCount, String result, Instant nextReviewAt) {
        return new ReviewEntry(questionId, wrongCount, 1, result, NOW.minusSeconds(3600), nextReviewAt, 1);
    }

    private static final class ScriptedRandom implements RandomGenerator {
        private final long draw;

        private ScriptedRandom(long draw) {
            this.draw = draw;
        }

        @Override
        public long nextLong() {
            return draw;
        }

        @Override
        public long nextLong(long bound) {
            return draw;
        }
    }
}
