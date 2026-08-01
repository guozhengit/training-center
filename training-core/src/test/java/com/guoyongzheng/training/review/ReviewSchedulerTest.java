package com.guoyongzheng.training.review;

import com.guoyongzheng.training.persistence.ReviewRepository.ReviewEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReviewSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void failureResetsToOneDayAndIncrementsWrongCountAtTheUtcClockBoundary() {
        ReviewEntry scheduled = new ReviewScheduler(CLOCK).schedule("B001", entry(7, 3, "PASSED"), false, null, false);

        assertThat(scheduled.intervalDays()).isEqualTo(1);
        assertThat(scheduled.wrongCount()).isEqualTo(4);
        assertThat(scheduled.reviewCount()).isEqualTo(8);
        assertThat(scheduled.lastResult()).isEqualTo("FAILED");
        assertThat(scheduled.lastAttemptAt()).isEqualTo(NOW);
        assertThat(scheduled.nextReviewAt()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
    }

    @Test
    void passingAdvancesOnlyThroughTheFixedOneThreeSevenFourteenDayLadder() {
        ReviewScheduler scheduler = new ReviewScheduler(CLOCK);

        assertThat(scheduler.schedule("B001", entry(1, 0, "FAILED"), true, null, false).intervalDays()).isEqualTo(3);
        assertThat(scheduler.schedule("B001", entry(3, 0, "PASSED"), true, null, false).intervalDays()).isEqualTo(7);
        assertThat(scheduler.schedule("B001", entry(7, 0, "PASSED"), true, null, false).intervalDays()).isEqualTo(14);
        assertThat(scheduler.schedule("B001", entry(14, 0, "PASSED"), true, null, false).intervalDays()).isEqualTo(14);
    }

    @Test
    void oralScoresBelowSixResetWhileSixAndSevenHoldAndEightAdvances() {
        ReviewScheduler scheduler = new ReviewScheduler(CLOCK);

        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 5, false).intervalDays()).isEqualTo(1);
        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 6, false).intervalDays()).isEqualTo(7);
        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 7, false).intervalDays()).isEqualTo(7);
        assertThat(scheduler.schedule("O001", entry(7, 0, "FAILED"), false, 8, false).intervalDays()).isEqualTo(14);
    }

    @Test
    void unlockingAnAnswerPreventsAdvancementButStillAllowsAFailureToReset() {
        ReviewScheduler scheduler = new ReviewScheduler(CLOCK);

        assertThat(scheduler.schedule("B001", entry(7, 0, "PASSED"), true, null, true).intervalDays()).isEqualTo(7);
        assertThat(scheduler.schedule("B001", entry(7, 0, "PASSED"), false, null, true).intervalDays()).isEqualTo(1);
    }

    @Test
    void acceptsOnlyOralTotalsFromZeroThroughTen() {
        ReviewScheduler scheduler = new ReviewScheduler(CLOCK);

        assertThatIllegalArgumentException().isThrownBy(() -> scheduler.schedule("O001", null, true, -1, false));
        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 0, false).intervalDays()).isEqualTo(1);
        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 10, false).intervalDays()).isEqualTo(14);
        assertThatIllegalArgumentException().isThrownBy(() -> scheduler.schedule("O001", null, true, 11, false));
    }

    @Test
    void weakDimensionsPreventAdvancementEvenOnAnEightPointPass() {
        ReviewScheduler scheduler = new ReviewScheduler(CLOCK);

        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 8, false, "factRestraint").intervalDays())
                .isEqualTo(7);
        assertThat(scheduler.schedule("O001", entry(7, 0, "PASSED"), true, 8, false, null).intervalDays())
                .isEqualTo(14);
        assertThat(scheduler.schedule("O001", entry(3, 0, "PASSED"), true, 6, false, "correctness").intervalDays())
                .isEqualTo(3);
    }

    @Test
    void focusDimensionsAreCarriedIntoTheScheduledEntry() {
        ReviewEntry scheduled = new ReviewScheduler(CLOCK)
                .schedule("O001", entry(7, 0, "PASSED"), true, 8, false, "structure,tradeoff");

        assertThat(scheduled.focusDimensions()).isEqualTo("structure,tradeoff");
        assertThat(scheduled.lastResult()).isEqualTo("PASSED");
        assertThat(scheduled.intervalDays()).isEqualTo(7);
    }

    @Test
    void criticalFailureOverridesAnOtherwisePassingOralTotalAndResetsToOneDay() {
        ReviewEntry scheduled = new ReviewScheduler(CLOCK)
                .schedule("O001", entry(7, 0, "PASSED"), false, 6, false, "structure,tradeoff,factRestraint", true);

        assertThat(scheduled.lastResult()).isEqualTo("FAILED");
        assertThat(scheduled.intervalDays()).isEqualTo(1);
        assertThat(scheduled.wrongCount()).isEqualTo(1);
        assertThat(scheduled.focusDimensions()).isEqualTo("structure,tradeoff,factRestraint");
    }

    private static ReviewEntry entry(int intervalDays, int wrongCount, String lastResult) {
        return new ReviewEntry("B001", wrongCount, 7, lastResult, NOW.minusSeconds(60), NOW, intervalDays);
    }
}
