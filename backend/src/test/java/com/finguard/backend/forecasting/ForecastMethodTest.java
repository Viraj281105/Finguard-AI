package com.finguard.backend.forecasting;

import com.finguard.backend.forecasting.service.ForecastMethod;
import com.finguard.backend.forecasting.service.ForecastMethod.Obs;
import com.finguard.backend.forecasting.service.ForecastMethod.Projection;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The method against examples worked out by hand. */
class ForecastMethodTest {

    private static List<Obs> series(double... values) {
        List<Obs> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new Obs(i, values[i]));
        }
        return out;
    }

    private static Projection project(List<Obs> obs, int horizon) {
        return ForecastMethod.project(obs, obs.get(obs.size() - 1).index(), horizon).orElseThrow();
    }

    @Test
    void levelIsTheMeanOfTheLastObservations() {
        // 100, 200, 300 -> mean 200; fewer than 5 points so no outlier handling, fewer than 6 so no trend
        Projection p = project(series(100, 200, 300), 3);

        assertThat(p.values()).extracting(Object::toString).containsExactly("200.00", "200.00", "200.00");
        assertThat(p.trendApplied()).isFalse();
        assertThat(p.observationsUsed()).isEqualTo(3);
    }

    @Test
    void levelUsesOnlyTheLastSixObservationsOfALongerHistory() {
        // 8 months: 1000, 1000, then 6 months of 100 -> the last six (all 100) set the level
        Projection p = project(series(1000, 1000, 100, 100, 100, 100, 100, 100), 1);
        assertThat(p.level()).isEqualTo(100.0);
    }

    @Test
    void fewerThanThreeObservationsGiveNoForecastAtAll() {
        assertThat(ForecastMethod.project(series(100, 200), 1, 3)).isEmpty();
        assertThat(ForecastMethod.project(List.of(), 0, 3)).isEmpty();
    }

    @Test
    void aPerfectUpwardTrendIsExtrapolatedWithDamping() {
        // 100,110,...,150: slope 10 exactly, fitted value at the last month 150.
        // damped sum for h = 1,2,3 is 0.8, 0.8+0.64, 0.8+0.64+0.512 -> 158, 164.4, 169.52
        Projection p = project(series(100, 110, 120, 130, 140, 150), 3);

        assertThat(p.trendApplied()).isTrue();
        assertThat(p.slopePerMonth()).isEqualTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(p.values()).extracting(Object::toString).containsExactly("158.00", "164.40", "169.52");
    }

    @Test
    void theTrendIsNotUsedWhenNoisyDataDoesNotSupportIt() {
        // Mean of 100,120,90,110,95,105 is 103.33; the fitted slope is about -0.86/month with a large standard error
        Projection p = project(series(100, 120, 90, 110, 95, 105), 2);

        assertThat(p.trendApplied()).isFalse();
        assertThat(p.values()).extracting(Object::toString).containsExactly("103.33", "103.33");
    }

    @Test
    void aTrendIsNeverFittedWithFewerThanSixMonths() {
        // Five months rising steadily: a trend would be obvious, but five points are too few to test one.
        Projection p = project(series(100, 120, 140, 160, 180), 1);
        assertThat(p.trendApplied()).isFalse();
        assertThat(p.level()).isEqualTo(140.0);
    }

    @Test
    void oneHugeMonthIsClippedSoItCannotDragTheLevel() {
        // 100 x4 and 1000: median 100, scale floor 10 => bounds 70..130, so 1000 counts as 130 -> mean 106
        Projection p = project(series(100, 100, 100, 100, 1000), 1);

        assertThat(p.outlierIndexes()).containsExactly(4);
        assertThat(p.values().get(0)).hasToString("106.00");
    }

    @Test
    void ordinaryVariationIsNotTreatedAsAnOutlier() {
        Projection p = project(series(100, 110, 95, 105, 100), 1);
        assertThat(p.outlierIndexes()).isEmpty();
    }

    @Test
    void aMissingMonthIsAGapOnTheCalendarNotAZero() {
        // Months 0,1,2,4,5,6 (month 3 missing) on the exact line 100 + 10*month. A method that squeezed the gap
        // out would see a steeper line; on the calendar axis the slope is exactly 10 and month 6 is fitted at 160.
        List<Obs> obs = List.of(new Obs(0, 100), new Obs(1, 110), new Obs(2, 120), new Obs(4, 140), new Obs(5, 150), new Obs(6, 160));
        Projection p = ForecastMethod.project(obs, 6, 1).orElseThrow();

        assertThat(p.slopePerMonth()).isEqualTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(p.values().get(0)).hasToString("168.00");
    }

    @Test
    void projectionsNeverGoBelowZero() {
        // Steeply falling: fitted end 0 and slope -100 would extrapolate negative
        Projection p = project(series(500, 400, 300, 200, 100, 0), 3);
        assertThat(p.values()).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void zeroValuedHistoryProjectsZeroOnlyBecauseZeroWasObserved() {
        Projection p = project(series(0, 0, 0, 0), 2);
        assertThat(p.values()).extracting(Object::toString).containsExactly("0.00", "0.00");
    }

    @Test
    void onlyObservationsAtOrBeforeTheOriginAreRead() {
        List<Obs> history = series(100, 100, 100);
        List<Obs> withFuture = new ArrayList<>(history);
        withFuture.add(new Obs(3, 9_999));
        withFuture.add(new Obs(4, 9_999));

        Optional<Projection> a = ForecastMethod.project(history, 2, 2);
        Optional<Projection> b = ForecastMethod.project(withFuture, 2, 2);

        assertThat(b.orElseThrow().values()).isEqualTo(a.orElseThrow().values());
    }

    @Test
    void observationsOlderThanTwelveMonthsAreIgnored() {
        List<Obs> obs = new ArrayList<>();
        obs.add(new Obs(0, 99_999)); // 20 months before the origin
        for (int i = 18; i <= 21; i++) {
            obs.add(new Obs(i, 100));
        }
        Projection p = ForecastMethod.project(obs, 21, 1).orElseThrow();
        assertThat(p.observationsUsed()).isEqualTo(4);
        assertThat(p.level()).isEqualTo(100.0);
    }

    @Test
    void calendarIndexesRoundTripAcrossYearAndLeapDayBoundaries() {
        assertThat(ForecastMethod.monthOf(ForecastMethod.indexOf(YearMonth.of(2026, 12)) + 1)).isEqualTo(YearMonth.of(2027, 1));
        assertThat(ForecastMethod.monthOf(ForecastMethod.indexOf(YearMonth.of(2024, 2)))).isEqualTo(YearMonth.of(2024, 2));
        assertThat(ForecastMethod.indexOf(YearMonth.of(2024, 3)) - ForecastMethod.indexOf(YearMonth.of(2024, 2))).isEqualTo(1);
    }

    @Test
    void theSameInputAlwaysGivesTheSameOutput() {
        List<Obs> obs = series(120, 95, 140, 110, 100, 130, 105);
        assertThat(project(obs, 4).values()).isEqualTo(project(obs, 4).values());
    }
}
