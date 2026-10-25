import { formatCurrency } from "../../../shared/utils/format";
import { VERDICT } from "../format";
import type { BacktestSummary } from "../types";

const SERIES_LABEL = { EXPENSES: "Spending", INCOME: "Income", NET: "Net cash flow" } as const;

/**
 * "How this method would have done on your own past months." Chronological rolling origin, each forecast
 * made only from months before it. Sample sizes are always shown; with fewer than 6 forecasts the verdict
 * is "inconclusive" - a handful of months cannot establish accuracy.
 */
export default function BacktestCard({ data }: { data: BacktestSummary }) {
  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900">How the method would have done on your history</h3>
      <p className="text-xs text-slate-500 mt-0.5">
        Evaluated on your own recorded months only ({data.usableMonths} usable). This is a look backwards, not a guarantee.
      </p>

      {data.status === "INSUFFICIENT_DATA" ? (
        <p className="text-xs text-slate-700 bg-slate-50 border border-slate-200 rounded-lg px-3 py-3 mt-3">
          Not enough history to score even one forecast. At least 4 usable months are needed.
        </p>
      ) : (
        <div className="mt-3 space-y-4">
          {data.series.map((s) => (
            <div key={s.series}>
              <h4 className="text-xs font-semibold text-slate-800 mb-1">{SERIES_LABEL[s.series]} · {s.observedMonths} months observed</h4>
              {s.horizons.length === 0 ? (
                <p className="text-xs text-slate-500">No forecast could be scored for this series.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs text-left">
                    <caption className="sr-only">{SERIES_LABEL[s.series]} backtest by horizon</caption>
                    <thead>
                      <tr className="text-slate-500 border-b border-slate-100">
                        <th scope="col" className="py-1.5 pr-3 font-medium">Months ahead</th>
                        <th scope="col" className="py-1.5 pr-3 font-medium text-right">Forecasts scored</th>
                        <th scope="col" className="py-1.5 pr-3 font-medium text-right">Method error (MAE)</th>
                        <th scope="col" className="py-1.5 pr-3 font-medium text-right">Last month repeats</th>
                        <th scope="col" className="py-1.5 pr-3 font-medium text-right">Mean of last 3</th>
                        <th scope="col" className="py-1.5 font-medium">Verdict</th>
                      </tr>
                    </thead>
                    <tbody>
                      {s.horizons.map((h) => (
                        <tr key={h.horizon} className="border-b border-slate-50">
                          <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">{h.horizon}</th>
                          <td className="py-2 pr-3 text-right tabular-nums">{h.samples}</td>
                          <td className="py-2 pr-3 text-right tabular-nums">
                            {formatCurrency(h.model.mae)}
                            <span className="block text-[11px] text-slate-500">{h.model.wapePercent === null ? "WAPE n/a (all actuals zero)" : `WAPE ${h.model.wapePercent.toFixed(1)}%`}</span>
                          </td>
                          <td className="py-2 pr-3 text-right tabular-nums text-slate-600">{formatCurrency(h.naive.mae)}</td>
                          <td className="py-2 pr-3 text-right tabular-nums text-slate-600">{formatCurrency(h.mean3.mae)}</td>
                          <td className={`py-2 ${h.verdict === "INCONCLUSIVE" ? "text-slate-500" : "text-slate-800"}`}>{VERDICT[h.verdict]}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <ul className="mt-4 list-disc pl-5 space-y-1 text-[11px] text-slate-500">
        {data.limitations.map((l) => <li key={l}>{l}</li>)}
      </ul>
    </div>
  );
}
