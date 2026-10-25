import { formatCurrency, formatDate } from "../../../shared/utils/format";
import { monthLabel, shortMonth, signed } from "../format";
import type { SavingsProjection } from "../types";

function Line({ data }: { data: SavingsProjection }) {
  const pts = data.points;
  if (pts.length < 1) return null;
  const values = [0, ...pts.map((p) => p.cumulativeNet)];
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const span = max - min || 1;
  const w = 520;
  const h = 150;
  const l = 54;
  const r = 14;
  const t = 12;
  const b = 26;
  const x = (i: number) => l + (pts.length === 1 ? (w - l - r) / 2 : (i / (pts.length - 1)) * (w - l - r));
  const y = (v: number) => t + (h - t - b) - ((v - min) / span) * (h - t - b);
  const path = pts.map((p, i) => `${i === 0 ? "M" : "L"}${x(i)},${y(p.cumulativeNet)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-auto max-w-xl" role="img"
      aria-label={`Projected cumulative surplus reaches ${formatCurrency(pts[pts.length - 1].cumulativeNet)} after ${pts.length} months. Hypothetical, not a balance.`}>
      <line x1={l} y1={y(0)} x2={w - r} y2={y(0)} stroke="#cbd5e1" strokeDasharray="3 3" />
      <text x={l - 6} y={y(0) + 3} textAnchor="end" fontSize="10" fill="#94a3b8">₹0</text>
      <text x={l - 6} y={y(max) + 3} textAnchor="end" fontSize="10" fill="#94a3b8">{formatCurrency(max)}</text>
      <path d={path} fill="none" stroke="#0284c7" strokeWidth="2.5" strokeDasharray="6 4" />
      {pts.map((p, i) => (
        <g key={p.month}>
          <circle cx={x(i)} cy={y(p.cumulativeNet)} r="3.5" fill="#fff" stroke="#0284c7" strokeWidth="2" />
          <text x={x(i)} y={h - 8} textAnchor="middle" fontSize="10" fill="#475569">{shortMonth(p.month)}</text>
        </g>
      ))}
    </svg>
  );
}

/**
 * The savings outlook keeps three different things visibly apart: the projected cash-flow surplus (a
 * calculation), goal contributions the user recorded (self-tracked planning figures), and a verified
 * bank balance (which FinGuard does not have).
 */
export default function SavingsOutlookCard({ data }: { data: SavingsProjection }) {
  const a = data.accounting;
  const unavailable = data.reliability === "UNAVAILABLE";

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm space-y-5">
      <div>
        <h3 className="text-base font-semibold text-slate-900">Savings outlook</h3>
        <p className="text-xs text-slate-500 mt-0.5">What the projected cash flow would leave over, kept apart from recorded goal progress and real bank balances.</p>
      </div>

      <div className="grid sm:grid-cols-3 gap-3">
        <section className="rounded-xl border border-sky-200 bg-sky-50/60 p-4" aria-label="Projected surplus">
          <p className="text-[11px] font-semibold text-sky-800 uppercase tracking-wider">Projected surplus</p>
          <p className="text-xl font-bold text-slate-900 mt-1">
            {unavailable || a.projectedSurplusOverHorizon === null ? "Not available" : signed(a.projectedSurplusOverHorizon, formatCurrency)}
          </p>
          <p className="text-[11px] text-slate-600 mt-1">
            {unavailable || data.averageMonthlyNet === null
              ? "Needs enough history and a regular income."
              : `About ${signed(data.averageMonthlyNet, formatCurrency)} a month over ${data.horizonMonths} months. A calculation, not money in an account.`}
          </p>
        </section>
        <section className="rounded-xl border border-slate-200 bg-slate-50 p-4" aria-label="Recorded goal contributions">
          <p className="text-[11px] font-semibold text-slate-700 uppercase tracking-wider">Goal contributions you recorded</p>
          <p className="text-xl font-bold text-slate-900 mt-1">{formatCurrency(a.recordedGoalContributions)}</p>
          <p className="text-[11px] text-slate-600 mt-1">Self-tracked planning figures. They are not verified bank deposits.</p>
        </section>
        <section className="rounded-xl border border-dashed border-slate-300 bg-white p-4" aria-label="Verified bank balance">
          <p className="text-[11px] font-semibold text-slate-700 uppercase tracking-wider">Verified bank balance</p>
          <p className="text-xl font-bold text-slate-400 mt-1">Not available</p>
          <p className="text-[11px] text-slate-600 mt-1">{a.verifiedBankBalanceNote}</p>
        </section>
      </div>

      {!unavailable && data.points.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-slate-800 mb-1">Projected cumulative surplus (hypothetical)</h4>
          <Line data={data} />
          <p className="text-[11px] text-slate-500 mt-1">
            No uncertainty range is drawn for the running total: errors in different months do not simply add up.
            {data.projectedSavingsRatePercent !== null && ` Projected savings rate ${data.projectedSavingsRatePercent.toFixed(1)}%.`}
          </p>
        </div>
      )}

      <div>
        <h4 className="text-xs font-semibold text-slate-800 mb-2">Goals against the projection</h4>
        {data.goals.length === 0 ? (
          <p className="text-xs text-slate-500">No active goals. Add one in Budgets &amp; Goals to compare it with the projected surplus.</p>
        ) : (
          <ul className="space-y-2.5">
            {data.goals.map((g) => (
              <li key={g.id} className="rounded-xl border border-slate-200 p-3.5 text-xs">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-semibold text-slate-900 text-sm">{g.name}</span>
                  <span className="text-slate-500">
                    {formatCurrency(g.recordedAmount)} recorded of {formatCurrency(g.targetAmount)}
                    {g.targetDate ? ` · by ${formatDate(g.targetDate)}` : ""}
                  </span>
                </div>
                <p className="mt-1.5 text-slate-600">
                  {g.requiredMonthlyPace !== null
                    ? `Needs about ${formatCurrency(g.requiredMonthlyPace)} a month to reach the target date. `
                    : "No target date set. "}
                  {g.onPaceIfAlone === null
                    ? ""
                    : g.onPaceIfAlone
                      ? "The projected surplus alone would cover that."
                      : "The projected surplus alone would not cover that."}
                </p>
                <p className="mt-0.5 text-slate-500">
                  {g.monthsIfAllSurplusUsed === null
                    ? "No projected surplus, so no completion estimate."
                    : g.monthsIfAllSurplusUsed === 0
                      ? "Already fully recorded."
                      : `If the entire projected surplus went to this goal alone: about ${g.monthsIfAllSurplusUsed} month${g.monthsIfAllSurplusUsed === 1 ? "" : "s"} (${monthLabel(g.completionMonthIfAllSurplusUsed ?? "")}).`}
                </p>
              </li>
            ))}
          </ul>
        )}
        {a.goalsOvercommitted !== null && a.totalRequiredGoalPace !== null && (
          <p role="status" className={`mt-3 text-xs rounded-lg px-3 py-2 border ${a.goalsOvercommitted ? "bg-amber-50 border-amber-200 text-amber-900" : "bg-emerald-50 border-emerald-200 text-emerald-800"}`}>
            All dated goals together need about {formatCurrency(a.totalRequiredGoalPace)} a month.{" "}
            {a.goalsOvercommitted
              ? "That is more than the projected monthly surplus, so the goals compete for the same money."
              : "That fits within the projected monthly surplus."}
          </p>
        )}
      </div>

      <p className="text-[11px] text-slate-500 border-t border-slate-100 pt-3">{data.disclaimer}</p>
    </div>
  );
}
