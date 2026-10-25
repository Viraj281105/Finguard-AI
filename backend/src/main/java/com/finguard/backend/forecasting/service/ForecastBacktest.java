package com.finguard.backend.forecasting.service;

import com.finguard.backend.forecasting.service.ForecastMethod.Obs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chronological rolling-origin evaluation of {@link ForecastMethod} against two simple baselines.
 *
 * <p>For every observed target month {@code m} and horizon {@code h}, the forecast origin is
 * {@code m - h}. Only observations with an index at or before the origin are handed to any forecaster
 * (the method, and both baselines), so information from the target month or later can never be used:
 * there is no random splitting and no future leakage by construction. {@code ForecastBacktestTest}
 * additionally proves that changing values after the origin changes nothing.
 *
 * <p>Baselines: <b>naive</b> (the last observed month repeats) and <b>mean3</b> (the mean of the last
 * three observed months).
 */
public final class ForecastBacktest {

    private ForecastBacktest() {
    }

    public record Point(int targetIndex, double actual, double model, double naive, double mean3) {
    }

    public record Run(int horizon, List<Point> points) {

        public int size() {
            return points.size();
        }

        /** Signed errors {@code actual - model}, the sample behind empirical bands. */
        public double[] modelErrors() {
            return points.stream().mapToDouble(p -> p.actual() - p.model()).toArray();
        }
    }

    /** Error summary of one forecaster over a run; {@code wape} is {@code null} when every actual is zero. */
    public record Metrics(int samples, double mae, Double wape, double rmse, double bias) {
    }

    public static Run run(List<Obs> observations, int horizon) {
        List<Obs> sorted = observations.stream().sorted(Comparator.comparingInt(Obs::index)).toList();
        List<Point> points = new ArrayList<>();
        for (Obs target : sorted) {
            int origin = target.index() - horizon;
            List<Obs> train = sorted.stream().filter(o -> o.index() <= origin).toList();
            Optional<ForecastMethod.Projection> projection = ForecastMethod.project(train, origin, horizon);
            if (projection.isEmpty()) {
                continue;
            }
            double model = projection.get().values().get(horizon - 1).doubleValue();
            double naive = train.get(train.size() - 1).value();
            double mean3 = train.subList(Math.max(0, train.size() - 3), train.size()).stream()
                    .mapToDouble(Obs::value).average().orElse(naive);
            points.add(new Point(target.index(), target.value(), model, naive, mean3));
        }
        return new Run(horizon, points);
    }

    public static Metrics metricsOf(Run run, java.util.function.ToDoubleFunction<Point> forecaster) {
        int n = run.size();
        if (n == 0) {
            return new Metrics(0, 0, null, 0, 0);
        }
        double abs = 0;
        double sq = 0;
        double signed = 0;
        double actuals = 0;
        for (Point p : run.points()) {
            double error = forecaster.applyAsDouble(p) - p.actual();
            abs += Math.abs(error);
            sq += error * error;
            signed += error;
            actuals += Math.abs(p.actual());
        }
        Double wape = actuals > 0 ? abs / actuals : null;
        return new Metrics(n, abs / n, wape, Math.sqrt(sq / n), signed / n);
    }

    /**
     * The run of a difference of two series (income minus expenses) at the same horizon, aligned on the
     * target months both runs scored. The net forecast is the income forecast minus the expense forecast,
     * so its past errors are exactly the differences of the components' errors.
     */
    public static Run netRun(Run income, Run expenses) {
        java.util.Map<Integer, Point> byTarget = new java.util.HashMap<>();
        expenses.points().forEach(p -> byTarget.put(p.targetIndex(), p));
        List<Point> points = new ArrayList<>();
        for (Point i : income.points()) {
            Point e = byTarget.get(i.targetIndex());
            if (e != null) {
                points.add(new Point(i.targetIndex(), i.actual() - e.actual(), i.model() - e.model(),
                        i.naive() - e.naive(), i.mean3() - e.mean3()));
            }
        }
        return new Run(income.horizon(), points);
    }

    public static Metrics model(Run run) {
        return metricsOf(run, Point::model);
    }

    public static Metrics naive(Run run) {
        return metricsOf(run, Point::naive);
    }

    public static Metrics mean3(Run run) {
        return metricsOf(run, Point::mean3);
    }

    /** Whether the method clearly beats, loses to, or is indistinguishable from the best baseline. */
    public static String verdict(Metrics model, Metrics naive, Metrics mean3) {
        int samples = model.samples();
        if (samples < 6) {
            return "INCONCLUSIVE";
        }
        double bestBaseline = Math.min(naive.mae(), mean3.mae());
        if (bestBaseline == 0) {
            return model.mae() == 0 ? "SIMILAR" : "BASELINE_BETTER";
        }
        double relative = (model.mae() - bestBaseline) / bestBaseline;
        if (relative <= -0.10) {
            return "MODEL_BETTER";
        }
        if (relative >= 0.10) {
            return "BASELINE_BETTER";
        }
        return "SIMILAR";
    }

    /**
     * Empirical band around a point forecast: the 10th and 90th percentile (nearest rank) of the past
     * forecast errors of this same method at this horizon. Empty with fewer than
     * {@value ForecastMethod#MIN_INTERVAL_SAMPLES} past errors - no band is better than an invented one.
     * It describes how this method erred on this history; it is not a calibrated probability.
     */
    public static Optional<double[]> band(Run run, double point, boolean nonNegative) {
        if (run.size() < ForecastMethod.MIN_INTERVAL_SAMPLES) {
            return Optional.empty();
        }
        double[] errors = run.modelErrors();
        double lower = point + ForecastMethod.quantile(errors, 0.10);
        double upper = point + ForecastMethod.quantile(errors, 0.90);
        if (nonNegative) {
            lower = Math.max(0, lower);
            upper = Math.max(0, upper);
        }
        return Optional.of(new double[]{Math.min(lower, point), Math.max(upper, point)});
    }
}
