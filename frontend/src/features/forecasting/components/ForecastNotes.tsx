import { RELIABILITY, monthLabel, tidyMoney } from "../format";
import type { HistoryInfo, Reliability } from "../types";

interface Props {
  reliability: Reliability;
  method: string;
  algorithmVersion: string;
  history: HistoryInfo;
  horizonMonths?: number;
  warnings: string[];
  assumptions: string[];
  intervalNote?: string;
}

/**
 * What a forecast rests on, said plainly: how much history, which method and version, what was
 * assumed and what looked wrong with the data. Shown above every forecast so a number is never seen
 * without its limits.
 */
export default function ForecastNotes({
  reliability,
  method,
  algorithmVersion,
  history,
  horizonMonths,
  warnings,
  assumptions,
  intervalNote,
}: Props) {
  const meta = RELIABILITY[reliability];
  const period =
    history.from && history.to ? `${monthLabel(history.from)} – ${monthLabel(history.to)}` : "no complete month yet";

  return (
    <section aria-label="What this forecast is based on" className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`text-[11px] font-semibold px-2.5 py-1 rounded-full border ${meta.tone}`}
          title={meta.help}
          data-testid="reliability-badge"
        >
          {meta.label}
        </span>
        <p className="text-xs text-slate-600">
          Based on <strong className="text-slate-800">{history.monthsUsed}</strong> complete month
          {history.monthsUsed === 1 ? "" : "s"} ({period}), {history.transactionsUsed} transactions
          {horizonMonths ? ` · looking ${horizonMonths} month${horizonMonths === 1 ? "" : "s"} ahead` : ""}
        </p>
      </div>
      <p className="text-[11px] text-slate-500 mt-1.5">{meta.help}</p>

      {warnings.length > 0 && (
        <ul className="mt-3 space-y-1.5" aria-label="Data-quality warnings">
          {warnings.map((w) => (
            <li key={w} className="flex gap-2 text-xs text-amber-900 bg-amber-50 border border-amber-200/80 rounded-lg px-3 py-2">
              <span aria-hidden="true">⚠️</span>
              <span>{tidyMoney(w)}</span>
            </li>
          ))}
        </ul>
      )}

      <details className="mt-3 group">
        <summary className="text-xs font-semibold text-sky-700 cursor-pointer select-none">
          Method and assumptions
        </summary>
        <div className="mt-2 text-xs text-slate-600 space-y-2">
          <p>
            Method <code className="text-slate-800">{method}</code> version{" "}
            <code className="text-slate-800">{algorithmVersion}</code>. Statistical and rule-based; no machine learning.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            {assumptions.map((a) => (
              <li key={a}>{tidyMoney(a)}</li>
            ))}
          </ul>
          {intervalNote && <p className="text-slate-500">{intervalNote}</p>}
        </div>
      </details>
    </section>
  );
}
