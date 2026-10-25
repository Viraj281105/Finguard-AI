import { formatCurrency } from "../../../shared/utils/format";
import { signed } from "../format";
import type { CashFlowForecast } from "../types";

function Tile({ label, value, tone = "text-slate-900", note }: { label: string; value: string; tone?: string; note?: string }) {
  return (
    <div className="p-3.5 bg-white rounded-xl border border-slate-200/80 shadow-sm">
      <p className="text-[11px] font-medium text-slate-500 uppercase tracking-wider">{label}</p>
      <p className={`text-lg font-bold mt-0.5 ${tone}`}>{value}</p>
      {note && <p className="text-[11px] text-slate-500 mt-0.5">{note}</p>}
    </div>
  );
}

/**
 * Totals over the horizon. Income, net and the savings rate are shown as "Not projected" when the
 * backend found no regular income - never as zero.
 */
export default function ForecastSummaryStrip({ data }: { data: CashFlowForecast }) {
  const s = data.summary;
  if (!s) return null;
  const n = data.horizonMonths;
  return (
    <section aria-label="Projected totals" className="grid grid-cols-2 lg:grid-cols-4 gap-3">
      <Tile label={`Projected income (${n}M)`} value={s.totalIncome === null ? "Not projected" : formatCurrency(s.totalIncome)}
        note={s.totalIncome === null ? "No regular income found in the history" : undefined} />
      <Tile label={`Projected spending (${n}M)`} value={formatCurrency(s.totalExpenses)} />
      <Tile label={`Projected net cash flow (${n}M)`}
        value={s.totalNet === null ? "Not projected" : signed(s.totalNet, formatCurrency)}
        tone={s.totalNet === null ? "text-slate-500" : s.totalNet >= 0 ? "text-emerald-600" : "text-rose-600"}
        note="Income minus spending: not a bank balance" />
      <Tile label="Projected savings rate"
        value={s.savingsRatePercent === null ? "Not projected" : `${s.savingsRatePercent.toFixed(1)}%`}
        tone={s.savingsRatePercent === null ? "text-slate-500" : "text-sky-700"}
        note="Share of projected income left over" />
    </section>
  );
}
