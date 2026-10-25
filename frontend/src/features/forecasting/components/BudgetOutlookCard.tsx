import { formatCurrency } from "../../../shared/utils/format";
import { RISK, monthLabel, tidyMoney } from "../format";
import type { BudgetOutlook, BudgetOutlookItem } from "../types";

function Bar({ item }: { item: BudgetOutlookItem }) {
  const high = item.expectedHigh ?? item.actualToDate;
  const scale = Math.max(item.budgetAmount, high, item.actualToDate) * 1.08 || 1;
  const pct = (v: number) => `${Math.min(100, (v / scale) * 100)}%`;
  const low = item.expectedLow ?? item.actualToDate;
  return (
    <div
      className="relative h-3 rounded-full bg-slate-100 overflow-hidden"
      role="img"
      aria-label={`Spent ${formatCurrency(item.actualToDate)} of ${formatCurrency(item.budgetAmount)}${
        item.expectedLow !== null && item.expectedHigh !== null
          ? `; expected by month end between ${formatCurrency(item.expectedLow)} and ${formatCurrency(item.expectedHigh)}`
          : ""
      }`}
    >
      {item.expectedHigh !== null && (
        <div className="absolute inset-y-0 bg-sky-200/80" style={{ left: pct(low), width: `calc(${pct(item.expectedHigh)} - ${pct(low)})` }} />
      )}
      <div
        className={`absolute inset-y-0 left-0 rounded-full ${item.actualToDate > item.budgetAmount ? "bg-rose-500" : "bg-sky-600"}`}
        style={{ width: pct(item.actualToDate) }}
      />
      <div className="absolute inset-y-0 w-0.5 bg-slate-900" style={{ left: pct(item.budgetAmount) }} title="Budget limit" />
    </div>
  );
}

function Item({ item }: { item: BudgetOutlookItem }) {
  const risk = RISK[item.risk];
  const completed = item.risk === "COMPLETED_WITHIN" || item.risk === "COMPLETED_OVER";
  return (
    <li className="py-4 border-b border-slate-100 last:border-0">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
        <h4 className="text-sm font-semibold text-slate-900">{item.label}</h4>
        <span className={`text-[11px] font-semibold px-2.5 py-0.5 rounded-full border ${risk.tone}`}>{risk.label}</span>
      </div>
      <Bar item={item} />
      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1 mt-2.5 text-xs">
        <div><dt className="text-slate-500">Spent so far</dt><dd className="font-semibold text-slate-900">{formatCurrency(item.actualToDate)}</dd></div>
        <div><dt className="text-slate-500">Budget</dt><dd className="font-semibold text-slate-900">{formatCurrency(item.budgetAmount)}</dd></div>
        <div>
          <dt className="text-slate-500">{item.remainingBudget < 0 ? "Over by" : "Remaining"}</dt>
          <dd className={`font-semibold ${item.remainingBudget < 0 ? "text-rose-600" : "text-slate-900"}`}>{formatCurrency(Math.abs(item.remainingBudget))}</dd>
        </div>
        <div>
          <dt className="text-slate-500">{completed ? "Final" : "Expected by month end"}</dt>
          <dd className="font-semibold text-slate-900">
            {completed
              ? formatCurrency(item.actualToDate)
              : item.risk === "EXCEEDED"
                ? `At least ${formatCurrency(item.actualToDate)}`
                : item.expectedLow === null || item.expectedHigh === null
                ? "Not enough data"
                : item.expectedLow === item.expectedHigh
                  ? formatCurrency(item.expectedHigh)
                  : `${formatCurrency(item.expectedLow)} – ${formatCurrency(item.expectedHigh)}`}
          </dd>
        </div>
      </dl>
      {item.estimatedOverrun !== null && item.estimatedOverrun > 0 && !completed && item.risk !== "EXCEEDED" && (
        <p className="text-xs text-rose-700 mt-1.5">Estimated overrun: about {formatCurrency(item.estimatedOverrun)} (central estimate).</p>
      )}
      {item.recurringStillDue > 0 && (
        <p className="text-xs text-slate-600 mt-1.5">Includes {formatCurrency(item.recurringStillDue)} of detected recurring payments not yet seen this month.</p>
      )}
      <p className="text-xs text-slate-600 mt-1.5">{tidyMoney(item.indeterminateReason ?? item.rule)}</p>
      {item.factors.length > 0 && (
        <details className="mt-1.5">
          <summary className="text-[11px] font-semibold text-sky-700 cursor-pointer">Why this assessment</summary>
          <ul className="list-disc pl-5 mt-1 space-y-0.5 text-[11px] text-slate-600">
            {item.factors.map((f) => <li key={f}>{tidyMoney(f)}</li>)}
          </ul>
        </details>
      )}
    </li>
  );
}

/**
 * Month-end outlook per budget: what is recorded, what is expected, the rule behind the label, and the
 * factors. Only spending that already happened is called certain; everything else is a range, and
 * "Not enough data" is a first-class answer.
 */
export default function BudgetOutlookCard({ data }: { data: BudgetOutlook }) {
  const items = [...(data.overall ? [data.overall] : []), ...data.categories];
  const period = data.period === "CURRENT" ? "month in progress" : data.period === "PAST" ? "finished month" : "upcoming month";

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
      <div className="mb-2">
        <h3 className="text-base font-semibold text-slate-900">Budget outlook: {monthLabel(data.month)}</h3>
        <p className="text-xs text-slate-500 mt-0.5">
          {period}
          {data.period === "CURRENT" && ` · data recorded through day ${data.daysWithRecordedData} of ${data.daysInMonth}`}. Dark mark = budget limit;
          light band = expected range at month end.
        </p>
      </div>
      {data.warnings.length > 0 && (
        <ul className="space-y-1 mb-2" aria-label="Budget outlook notes">
          {data.warnings.map((w) => (
            <li key={w} className="text-xs text-amber-900 bg-amber-50 border border-amber-200/80 rounded-lg px-3 py-1.5">{tidyMoney(w)}</li>
          ))}
        </ul>
      )}
      {items.length === 0 ? (
        <p className="text-xs text-slate-500 py-3">Set a monthly budget in Budgets &amp; Goals to see how this month is likely to end.</p>
      ) : (
        <ul>{items.map((i) => <Item key={i.budgetId} item={i} />)}</ul>
      )}
    </div>
  );
}
