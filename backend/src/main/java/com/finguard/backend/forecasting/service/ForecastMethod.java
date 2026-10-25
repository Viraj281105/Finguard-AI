package com.finguard.backend.forecasting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The forecasting method, {@value #NAME} {@value #VERSION}: a robust level with an optional, damped,
 * statistically tested trend. Pure functions of the observations they are given - no I/O, no clock,
 * no randomness - so every number is reproducible and testable by hand.
 *
 * <p>Observations are monthly values indexed by calendar month ({@link #indexOf}), so a missing month
 * is a missing month, never a zero and never a compressed gap.
 *
 * <p>Algorithm, for a forecast origin month {@code o} (only observations with index &le; {@code o}
 * are ever read - this is what makes backtesting leak-free):
 * <ol>
 *   <li>Take the observations of the last {@value #LOOKBACK_MONTHS} calendar months. Fewer than
 *       {@value #MIN_OBSERVATIONS} means no forecast at all.</li>
 *   <li>With at least {@value #MIN_OUTLIER_OBSERVATIONS} observations, clip outliers to
 *       median &plusmn; {@value #OUTLIER_MADS} &times; max(scaled MAD, 10 % of the median) (a single unusual
 *       month cannot drag the level).</li>
 *   <li>Level = mean of the last {@value #LEVEL_WINDOW} (clipped) observations.</li>
 *   <li>With at least {@value #MIN_TREND_OBSERVATIONS} observations, fit an ordinary-least-squares line
 *       on calendar months. The trend is used <em>only</em> if its slope is significantly different
 *       from zero ({@code |t| >= }{@value #TREND_T_THRESHOLD}); otherwise the forecast is the level.
 *       When used, the forecast is the fitted value at the last observation plus the slope times
 *       {@code sum(phi^k, k=1..h)} with damping {@code phi = }{@value #DAMPING}, so the trend fades
 *       instead of extrapolating linearly forever.</li>
 *   <li>Values are floored at zero (spend and income cannot be negative).</li>
 * </ol>
 * Seasonality is deliberately not modelled: it needs at least two full years, and a version of this
 * method with that evidence has not been built or evaluated.
 */
public final class ForecastMethod {

    public static final String NAME = "LEVEL_DAMPED_TREND";
    public static final String VERSION = "2.0.0";

    public static final int LOOKBACK_MONTHS = 12;
    public static final int MIN_OBSERVATIONS = 3;
    public static final int MIN_OUTLIER_OBSERVATIONS = 5;
    public static final int LEVEL_WINDOW = 6;
    public static final int MIN_TREND_OBSERVATIONS = 6;
    public static final double TREND_T_THRESHOLD = 2.0;
    public static final double DAMPING = 0.8;
    public static final double OUTLIER_MADS = 3.0;
    /** Lower bound of the outlier scale, as a fraction of the median. */
    public static final double OUTLIER_SCALE_FLOOR = 0.10;
    /** Past forecast errors needed before an empirical band is shown. */
    public static final int MIN_INTERVAL_SAMPLES = 8;

    private ForecastMethod() {
    }

    /** A monthly value; {@code index} is {@link #indexOf} of the calendar month. */
    public record Obs(int index, double value) {
    }

    public record Projection(
            List<BigDecimal> values,
            boolean trendApplied,
            double level,
            double slopePerMonth,
            List<Integer> outlierIndexes,
            int observationsUsed
    ) {
    }

    public static int indexOf(YearMonth month) {
        return month.getYear() * 12 + month.getMonthValue() - 1;
    }

    public static YearMonth monthOf(int index) {
        return YearMonth.of(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1);
    }

    /**
     * Projects {@code horizon} months after {@code originIndex}. Empty when there is not enough evidence.
     * Only observations with {@code index <= originIndex} are read.
     */
    public static Optional<Projection> project(List<Obs> observations, int originIndex, int horizon) {
        List<Obs> window = observations.stream()
                .filter(o -> o.index() <= originIndex && o.index() > originIndex - LOOKBACK_MONTHS)
                .sorted(Comparator.comparingInt(Obs::index))
                .toList();
        int n = window.size();
        if (n < MIN_OBSERVATIONS || horizon < 1) {
            return Optional.empty();
        }

        double[] y = window.stream().mapToDouble(Obs::value).toArray();
        List<Integer> outliers = new ArrayList<>();
        if (n >= MIN_OUTLIER_OBSERVATIONS) {
            double median = median(y);
            double[] deviations = Arrays.stream(y).map(v -> Math.abs(v - median)).toArray();
            // Scale = scaled MAD, but never below 10 % of the median: a perfectly stable series (MAD = 0)
            // must still tolerate ordinary variation, and a single huge month must still be caught.
            double scale = Math.max(1.4826 * median(deviations), OUTLIER_SCALE_FLOOR * Math.abs(median));
            if (scale > 0) {
                double low = median - OUTLIER_MADS * scale;
                double high = median + OUTLIER_MADS * scale;
                for (int i = 0; i < n; i++) {
                    if (y[i] < low || y[i] > high) {
                        outliers.add(window.get(i).index());
                        y[i] = Math.min(Math.max(y[i], low), high);
                    }
                }
            }
        }

        int from = Math.max(0, n - LEVEL_WINDOW);
        double level = Arrays.stream(y, from, n).average().orElse(0);

        boolean trend = false;
        double slope = 0;
        double anchor = level;
        if (n >= MIN_TREND_OBSERVATIONS) {
            double meanX = window.stream().mapToDouble(Obs::index).average().orElse(0);
            double meanY = Arrays.stream(y).average().orElse(0);
            double sxx = 0;
            double sxy = 0;
            for (int i = 0; i < n; i++) {
                double dx = window.get(i).index() - meanX;
                sxx += dx * dx;
                sxy += dx * (y[i] - meanY);
            }
            if (sxx > 0) {
                double b = sxy / sxx;
                double sse = 0;
                for (int i = 0; i < n; i++) {
                    double fitted = meanY + b * (window.get(i).index() - meanX);
                    sse += (y[i] - fitted) * (y[i] - fitted);
                }
                double se = Math.sqrt(sse / (n - 2) / sxx);
                double t = se == 0 ? (b == 0 ? 0 : Double.POSITIVE_INFINITY) : Math.abs(b) / se;
                if (t >= TREND_T_THRESHOLD) {
                    trend = true;
                    slope = b;
                    anchor = meanY + b * (window.get(n - 1).index() - meanX);
                }
            }
        }

        List<BigDecimal> values = new ArrayList<>();
        double damped = 0;
        double phiPower = 1;
        for (int h = 1; h <= horizon; h++) {
            phiPower *= DAMPING;
            damped += phiPower;
            double v = trend ? anchor + slope * damped : level;
            values.add(money(Math.max(0, v)));
        }
        return Optional.of(new Projection(values, trend, level, slope, outliers, n));
    }

    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /** Nearest-rank quantile of an unsorted sample. */
    static double quantile(double[] sample, double q) {
        double[] sorted = sample.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }
}
