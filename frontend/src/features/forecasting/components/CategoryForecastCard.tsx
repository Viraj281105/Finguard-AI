import { formatCurrency } from "../../../shared/utils/format";
import { RELIABILITY, TREND, monthLabel } from "../format";
import type { CategoryForecastResponse } from "../types";

/**
 * Category-level projections. A category only appears with its own forecast if it has enough spending
 * history of its own; the rest are listed with the reason instead of being silently dropped or guessed.
 */
export default function CategoryForecastCard({ data }: { data: CategoryForecastResponse }) {
  const rising = new Set(data.risingCategories);

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
      <div className="mb-3">
        <h3 className="text-base font-semibold text-slate-900">Spending by category</h3>
        <p className="text-xs text-slate-500 mt-0.5">
          Projected monthly spending per category. Transfers, card payments and investments are not spending and are excluded.
        </p>
      </div>

      {data.reliability === "UNAVAILABLE" ? (
        <p className="text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-lg px-3 py-3">
          Category projections need at least {data.history.minMonthsLimited} complete months of history; {data.history.monthsUsed} found.
        </p>
      ) : (
        <>
          {data.risingCategories.length > 0 && (
            <p role="status" className="text-xs text-rose-800 bg-rose-50 border border-rose-200 rounded-lg px-3 py-2 mb-3">
              Rising trend (statistically tested, at least 3% a month): {data.categories.filter((c) => rising.has(c.category)).map((c) => c.label).join(", ")}.
            </p>
          )}
          {data.categories.length === 0 ? (
            <p className="text-xs text-slate-500 py-4">No category has enough spending history for its own projection yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <caption className="sr-only">Projected monthly spending by category</caption>
                <thead>
                  <tr className="text-slate-500 border-b border-slate-100">
                    <th scope="col" className="py-2 pr-3 font-medium">Category</th>
                    <th scope="col" className="py-2 pr-3 font-medium text-right">Recent monthly</th>
                    <th scope="col" className="py-2 pr-3 font-medium text-right">Next month</th>
                    <th scope="col" className="py-2 pr-3 font-medium text-right">Over horizon</th>
                    <th scope="col" className="py-2 pr-3 font-medium">Trend</th>
                    <th scope="col" className="py-2 font-medium">Evidence</th>
                  </tr>
                </thead>
                <tbody>
                  {data.categories.map((c) => {
                    const first = c.monthly[0];
                    return (
                      <tr key={c.category} className="border-b border-slate-50 align-top">
                        <th scope="row" className="py-2.5 pr-3 font-semibold text-slate-900">
                          {c.label}
                          {c.recurringMonthly > 0 && (
                            <span className="block text-[11px] font-normal text-slate-500">
                              includes recurring {formatCurrency(c.recurringMonthly)}/mo already in history
                            </span>
                          )}
                        </th>
                        <td className="py-2.5 pr-3 text-right tabular-nums text-slate-600">{formatCurrency(c.recentMonthlyAverage)}</td>
                        <td className="py-2.5 pr-3 text-right tabular-nums font-semibold text-slate-900">
                          {formatCurrency(first.point)}
                          {first.lower !== null && first.upper !== null && (
                            <span className="block text-[11px] font-normal text-violet-700">
                              {formatCurrency(first.lower)} – {formatCurrency(first.upper)}
                            </span>
                          )}
                        </td>
                        <td className="py-2.5 pr-3 text-right tabular-nums text-slate-700">{formatCurrency(c.projectedTotal)}</td>
                        <td className="py-2.5 pr-3">
                          <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full border ${TREND[c.trend].tone}`}>
                            {TREND[c.trend].label}
                            {c.trendPercentPerMonth !== null && ` ${c.trendPercentPerMonth > 0 ? "+" : ""}${c.trendPercentPerMonth}%/mo`}
                          </span>
                        </td>
                        <td className="py-2.5">
                          <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full border ${RELIABILITY[c.reliability].tone}`}>
                            {RELIABILITY[c.reliability].label}
                          </span>
                          <span className="block text-[11px] text-slate-500 mt-0.5 max-w-[16rem]">{c.explanation}</span>
                          {c.outlierMonths.length > 0 && (
                            <span className="block text-[11px] text-amber-700 mt-0.5">
                              Unusual month(s) limited: {c.outlierMonths.map(monthLabel).join(", ")}
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {data.withoutForecast.length > 0 && (
            <details className="mt-4">
              <summary className="text-xs font-semibold text-sky-700 cursor-pointer">
                {data.withoutForecast.length} categor{data.withoutForecast.length === 1 ? "y" : "ies"} without a projection
              </summary>
              <ul className="mt-2 space-y-1 text-xs text-slate-600">
                {data.withoutForecast.map((w) => (
                  <li key={w.category}><strong className="text-slate-800">{w.label}:</strong> {w.reason}</li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </div>
  );
}
