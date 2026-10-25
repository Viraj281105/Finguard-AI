package com.finguard.backend.forecasting;

import com.finguard.backend.forecasting.service.ForecastBacktest;
import com.finguard.backend.forecasting.service.ForecastBacktest.Metrics;
import com.finguard.backend.forecasting.service.ForecastBacktest.Point;
import com.finguard.backend.forecasting.service.ForecastBacktest.Run;
import com.finguard.backend.forecasting.service.ForecastMethod.Obs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ForecastBacktestTest {

    private static List<Obs> series(double... values) {
        List<Obs> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new Obs(i, values[i]));
        }
        return out;
    }

    // ---- no future leakage ------------------------------------------------------------------

    @Test
    void aForecastNeverSeesTheTargetMonthOrAnythingAfterIt() {
        double[] base = {100, 120, 90, 110, 95, 105, 130, 100, 115, 98};
        Run original = ForecastBacktest.run(series(base), 1);

        // Replace every value from index t onwards with garbage and re-run: the prediction FOR target t
        // (made at origin t-1) must be identical, only the actual may differ.
        for (Point p : original.points()) {
            int t = p.targetIndex();
            double[] altered = base.clone();
            for (int i = t; i < altered.length; i++) {
                altered[i] = 1_000_000 + i;
            }
            Run changed = ForecastBacktest.run(series(altered), 1);
            Point same = changed.points().stream().filter(q -> q.targetIndex() == t).findFirst().orElseThrow();
            assertThat(same.model()).as("model prediction for month %d", t).isEqualTo(p.model());
            assertThat(same.naive()).as("naive prediction for month %d", t).isEqualTo(p.naive());
            assertThat(same.mean3()).as("mean3 prediction for month %d", t).isEqualTo(p.mean3());
        }
    }

    @Test
    void multiStepForecastsRespectTheHorizonGap() {
        double[] base = {100, 120, 90, 110, 95, 105, 130, 100, 115, 98};
        int h = 3;
        Run original = ForecastBacktest.run(series(base), h);
        for (Point p : original.points()) {
            int origin = p.targetIndex() - h;
            double[] altered = base.clone();
            for (int i = origin + 1; i < altered.length; i++) {
                altered[i] = 7_777_777; // everything after the origin, including months between origin and target
            }
            Run changed = ForecastBacktest.run(series(altered), h);
            Point same = changed.points().stream().filter(q -> q.targetIndex() == p.targetIndex()).findFirst().orElseThrow();
            assertThat(same.model()).isEqualTo(p.model());
        }
    }

    @Test
    void theFirstScoredTargetNeedsAtLeastThreeEarlierMonths() {
        Run run = ForecastBacktest.run(series(100, 100, 100, 100, 100), 1);
        // targets with >= 3 training months: indexes 3 and 4
        assertThat(run.points()).extracting(Point::targetIndex).containsExactly(3, 4);
    }

    @Test
    void aMissingTargetMonthIsNotScoredAsZero() {
        List<Obs> obs = List.of(new Obs(0, 100), new Obs(1, 100), new Obs(2, 100), new Obs(3, 100), new Obs(5, 100));
        Run run = ForecastBacktest.run(obs, 1);
        assertThat(run.points()).extracting(Point::targetIndex).containsExactly(3, 5); // month 4 is not observed -> not a target
    }

    // ---- metrics by hand ----------------------------------------------------------------------

    @Test
    void errorMetricsMatchAHandCalculation() {
        // actual 100 / forecast 110 (error +10);  actual 200 / forecast 150 (error -50)
        Run run = new Run(1, List.of(new Point(1, 100, 110, 0, 0), new Point(2, 200, 150, 0, 0)));
        Metrics m = ForecastBacktest.model(run);

        assertThat(m.samples()).isEqualTo(2);
        assertThat(m.mae()).isEqualTo(30.0);                                    // (10+50)/2
        assertThat(m.rmse()).isEqualTo(Math.sqrt((100 + 2500) / 2.0), offset(1e-9));
        assertThat(m.bias()).isEqualTo(-20.0);                                  // mean(forecast-actual) = (10-50)/2
        assertThat(m.wape()).isEqualTo(60.0 / 300.0, offset(1e-12));            // 0.2
    }

    @Test
    void wapeIsUndefinedNotInfiniteWhenEveryActualIsZero() {
        Run run = new Run(1, List.of(new Point(1, 0, 40, 0, 0), new Point(2, 0, 10, 0, 0)));
        Metrics m = ForecastBacktest.model(run);

        assertThat(m.wape()).isNull();
        assertThat(m.mae()).isEqualTo(25.0);
    }

    @Test
    void aZeroActualAmongNonZeroOnesDoesNotBreakTheWeightedError() {
        Run run = new Run(1, List.of(new Point(1, 0, 10, 0, 0), new Point(2, 100, 100, 0, 0)));
        assertThat(ForecastBacktest.model(run).wape()).isEqualTo(10.0 / 100.0, offset(1e-12));
    }

    @Test
    void emptyRunsHaveZeroSamples() {
        Metrics m = ForecastBacktest.model(new Run(1, List.of()));
        assertThat(m.samples()).isZero();
        assertThat(m.wape()).isNull();
    }

    // ---- verdicts ---------------------------------------------------------------------------

    @Test
    void fewerThanSixScoredForecastsIsInconclusiveWhateverTheNumbersSay() {
        Metrics model = new Metrics(5, 1, 0.01, 1, 0);
        Metrics baseline = new Metrics(5, 100, 1.0, 100, 0);
        assertThat(ForecastBacktest.verdict(model, baseline, baseline)).isEqualTo("INCONCLUSIVE");
    }

    @Test
    void verdictComparesAgainstTheBestBaselineWithATenPercentMargin() {
        Metrics naive = new Metrics(8, 100, 0.5, 100, 0);
        Metrics mean3 = new Metrics(8, 80, 0.4, 80, 0);
        assertThat(ForecastBacktest.verdict(new Metrics(8, 60, 0.3, 60, 0), naive, mean3)).isEqualTo("MODEL_BETTER");  // -25 % vs 80
        assertThat(ForecastBacktest.verdict(new Metrics(8, 85, 0.4, 85, 0), naive, mean3)).isEqualTo("SIMILAR");       // +6 %
        assertThat(ForecastBacktest.verdict(new Metrics(8, 95, 0.5, 95, 0), naive, mean3)).isEqualTo("BASELINE_BETTER"); // +19 %
    }

    @Test
    void theMethodBeatsBothBaselinesOnACleanLinearTrend() {
        // Synthetic: 14 months of exactly 1000 + 50*m. Naive lags by 50, mean3 by 100; the damped trend lags less.
        double[] values = new double[14];
        for (int i = 0; i < values.length; i++) {
            values[i] = 1000 + 50 * i;
        }
        Run run = ForecastBacktest.run(series(values), 1);
        Metrics model = ForecastBacktest.model(run);

        assertThat(model.mae()).isLessThan(ForecastBacktest.naive(run).mae());
        assertThat(model.mae()).isLessThan(ForecastBacktest.mean3(run).mae());
        assertThat(ForecastBacktest.verdict(model, ForecastBacktest.naive(run), ForecastBacktest.mean3(run))).isEqualTo("MODEL_BETTER");
    }

    // ---- empirical bands ------------------------------------------------------------------------

    @Test
    void noBandWithFewerThanEightPastErrors() {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            points.add(new Point(i, 100 + i, 100, 0, 0));
        }
        assertThat(ForecastBacktest.band(new Run(1, points), 500, true)).isEmpty();
    }

    @Test
    void theBandIsThePointPlusTheTenthAndNinetiethPercentileOfPastErrors() {
        // errors (actual - forecast): -40,-30,-20,-10,10,20,30,40.  Nearest rank: q10 = rank 1 = -40, q90 = rank 8 = +40
        double[] errors = {-40, -30, -20, -10, 10, 20, 30, 40};
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < errors.length; i++) {
            points.add(new Point(i, 100 + errors[i], 100, 0, 0));
        }
        double[] band = ForecastBacktest.band(new Run(1, points), 1000, true).orElseThrow();

        assertThat(band[0]).isEqualTo(960.0);
        assertThat(band[1]).isEqualTo(1040.0);
    }

    @Test
    void nonNegativeSeriesHaveTheirLowerBandClampedAtZero() {
        double[] errors = {-40, -30, -20, -10, 10, 20, 30, 40};
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < errors.length; i++) {
            points.add(new Point(i, 100 + errors[i], 100, 0, 0));
        }
        double[] clamped = ForecastBacktest.band(new Run(1, points), 10, true).orElseThrow();
        double[] free = ForecastBacktest.band(new Run(1, points), 10, false).orElseThrow();

        assertThat(clamped[0]).isEqualTo(0.0);
        assertThat(free[0]).isEqualTo(-30.0);
    }

    @Test
    void theBandAlwaysContainsThePointForecast() {
        // all past errors are positive: the raw quantiles would sit above the point
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            points.add(new Point(i, 150 + i, 100, 0, 0));
        }
        double[] band = ForecastBacktest.band(new Run(1, points), 100, true).orElseThrow();
        assertThat(band[0]).isLessThanOrEqualTo(100.0);
        assertThat(band[1]).isGreaterThanOrEqualTo(100.0);
    }

    @Test
    void netRunsAlignOnTargetMonthsAndSubtractComponentwise() {
        Run income = new Run(1, List.of(new Point(3, 1000, 900, 950, 940), new Point(4, 1000, 1000, 1000, 990)));
        Run expenses = new Run(1, List.of(new Point(4, 600, 650, 590, 610), new Point(5, 600, 600, 600, 600)));

        Run net = ForecastBacktest.netRun(income, expenses);

        assertThat(net.points()).hasSize(1);
        Point p = net.points().get(0);
        assertThat(p.targetIndex()).isEqualTo(4);
        assertThat(p.actual()).isEqualTo(400.0);   // 1000 - 600
        assertThat(p.model()).isEqualTo(350.0);    // 1000 - 650
    }
}
