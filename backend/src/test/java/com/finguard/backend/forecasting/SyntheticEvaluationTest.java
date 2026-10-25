package com.finguard.backend.forecasting;

import com.finguard.backend.forecasting.service.ForecastBacktest;
import com.finguard.backend.forecasting.service.ForecastBacktest.Metrics;
import com.finguard.backend.forecasting.service.ForecastBacktest.Run;
import com.finguard.backend.forecasting.service.ForecastMethod;
import com.finguard.backend.forecasting.service.ForecastMethod.Obs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYNTHETIC evaluation of the forecasting method. Every series here is generated from a fixed random
 * seed by the formulae below; nothing is real user data, so these numbers say how the method behaves on
 * artificial patterns that were chosen to probe it - not how accurate it is on anybody's finances.
 *
 * <p>Reproduce: {@code cd backend; .\mvnw -B -Dtest=SyntheticEvaluationTest test}. The report is written to
 * {@code backend/target/forecast-evaluation/synthetic-evaluation.md} and printed. It is fully deterministic.
 */
class SyntheticEvaluationTest {

    private static final int SEEDS = 40;
    private static final int MONTHS = 24;
    private static final int LONG_MONTHS = 36;

    private record Pattern(String name, String description, BiFunction<Random, Integer, Double> value) {
    }

    private static final List<Pattern> PATTERNS = List.of(
            new Pattern("STABLE", "30,000 + Gaussian noise (sd 6 %)",
                    (r, m) -> 30_000 * (1 + 0.06 * r.nextGaussian())),
            new Pattern("TREND", "20,000 growing 3 % a month + noise (sd 4 %)",
                    (r, m) -> 20_000 * Math.pow(1.03, m) * (1 + 0.04 * r.nextGaussian())),
            new Pattern("STEP", "30,000, then 40,000 from month 12 + noise (sd 5 %)",
                    (r, m) -> (m < 12 ? 30_000 : 40_000) * (1 + 0.05 * r.nextGaussian())),
            new Pattern("SPIKY", "30,000 + noise (sd 5 %), 15 % chance of a doubled month",
                    (r, m) -> 30_000 * (1 + 0.05 * r.nextGaussian()) * (r.nextDouble() < 0.15 ? 2 : 1)));

    private static List<Obs> generate(Pattern p, long seed, int months) {
        Random random = new Random(seed * 7919L + p.name().hashCode());
        List<Obs> out = new ArrayList<>();
        for (int m = 0; m < months; m++) {
            out.add(new Obs(m, Math.max(0, p.value().apply(random, m))));
        }
        return out;
    }

    private record Aggregate(int samples, double mae, double wape) {
    }

    /** Pools the absolute errors of all seeds (sum |e| / sum actual): WAPE over the pooled sample. */
    private static Aggregate aggregate(Pattern p, int horizon, java.util.function.Function<Run, Metrics> which) {
        double absSum = 0;
        double actualSum = 0;
        int samples = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Run run = ForecastBacktest.run(generate(p, seed, MONTHS), horizon);
            Metrics m = which.apply(run);
            absSum += m.mae() * m.samples();
            samples += m.samples();
            for (var point : run.points()) {
                actualSum += Math.abs(point.actual());
            }
        }
        return new Aggregate(samples, absSum / samples, absSum / actualSum);
    }

    private static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Synthetic forecast evaluation\n\n");
        sb.append("SYNTHETIC data only (fixed seeds, formulae below). Method ")
                .append(ForecastMethod.NAME).append(' ').append(ForecastMethod.VERSION)
                .append("; ").append(SEEDS).append(" series per pattern, ").append(MONTHS)
                .append(" months each; chronological rolling origin (no future data at any origin).\n\n");
        sb.append("MAE in rupees; WAPE = sum|error| / sum(actual) over the pooled sample; n = scored forecasts.\n\n");
        sb.append("| Pattern | Horizon | n | Model MAE | Naive MAE | Mean-3 MAE | Model WAPE | Naive WAPE | Mean-3 WAPE |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Pattern p : PATTERNS) {
            for (int h : new int[]{1, 3}) {
                Aggregate model = aggregate(p, h, ForecastBacktest::model);
                Aggregate naive = aggregate(p, h, ForecastBacktest::naive);
                Aggregate mean3 = aggregate(p, h, ForecastBacktest::mean3);
                sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %,.0f | %,.0f | %,.0f | %.1f%% | %.1f%% | %.1f%% |%n",
                        p.name(), h, model.samples(), model.mae(), naive.mae(), mean3.mae(),
                        model.wape() * 100, naive.wape() * 100, mean3.wape() * 100));
            }
        }
        sb.append("\nPatterns: ");
        PATTERNS.forEach(p -> sb.append("**").append(p.name()).append("** ").append(p.description()).append("; "));
        sb.append("\n\n## Empirical band coverage (horizon 1)\n\n");
        sb.append("For every origin from month 12 to ").append(LONG_MONTHS - 2).append(" of a ").append(LONG_MONTHS)
                .append("-month series, the band is built only from forecast errors available at that origin and checked against the ")
                .append("next month. Nominal width: 10th to 90th percentile of past errors, i.e. about 80 % if errors were stationary.\n\n");
        sb.append("| Pattern | Bands checked | Actual inside band |\n|---|---:|---:|\n");
        for (Pattern p : PATTERNS) {
            int checked = 0;
            int inside = 0;
            for (long seed = 1; seed <= SEEDS; seed++) {
                List<Obs> series = generate(p, seed, LONG_MONTHS);
                for (int origin = 12; origin <= LONG_MONTHS - 2; origin++) {
                    final int originIndex = origin;
                    List<Obs> known = series.stream().filter(o -> o.index() <= originIndex).toList();
                    Optional<ForecastMethod.Projection> projection = ForecastMethod.project(known, origin, 1);
                    if (projection.isEmpty()) {
                        continue;
                    }
                    double point = projection.get().values().get(0).doubleValue();
                    Optional<double[]> band = ForecastBacktest.band(ForecastBacktest.run(known, 1), point, true);
                    if (band.isEmpty()) {
                        continue;
                    }
                    double actual = series.get(origin + 1).value();
                    checked++;
                    if (actual >= band.get()[0] && actual <= band.get()[1]) {
                        inside++;
                    }
                }
            }
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f%% |%n", p.name(), checked, 100.0 * inside / checked));
        }
        return sb.toString();
    }

    @Test
    void theEvaluationIsDeterministicAndItsReportIsWritten() throws IOException {
        String first = render();
        String second = render();
        assertThat(second).isEqualTo(first);

        Path out = Path.of("target", "forecast-evaluation", "synthetic-evaluation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, first);
        System.out.println(first);
    }

    @Test
    void onNoiseAroundAConstantLevelAveragingBeatsRepeatingLastMonth() {
        Pattern stable = PATTERNS.get(0);
        for (int h : new int[]{1, 3}) {
            assertThat(aggregate(stable, h, ForecastBacktest::model).mae())
                    .as("model vs naive on STABLE at horizon %d", h)
                    .isLessThan(aggregate(stable, h, ForecastBacktest::naive).mae());
        }
    }

    @Test
    void onAGrowthTrendTheMethodBeatsBothBaselines() {
        Pattern trend = PATTERNS.get(1);
        double model = aggregate(trend, 1, ForecastBacktest::model).mae();
        assertThat(model).isLessThan(aggregate(trend, 1, ForecastBacktest::naive).mae());
        assertThat(model).isLessThan(aggregate(trend, 1, ForecastBacktest::mean3).mae());
    }

    @Test
    void everyPatternProducesEnoughScoredForecastsToReport() {
        for (Pattern p : PATTERNS) {
            assertThat(aggregate(p, 1, ForecastBacktest::model).samples()).isGreaterThan(SEEDS * 15);
        }
    }
}
