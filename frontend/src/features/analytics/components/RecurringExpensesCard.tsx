import { formatCurrency, formatDate } from "../../../shared/utils/format";
import type { RecurringExpenses } from "../types";

interface RecurringExpensesCardProps {
  data: RecurringExpenses | null;
}

export default function RecurringExpensesCard({ data }: RecurringExpensesCardProps) {
  if (!data || data.items.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
        <h3 className="text-base font-semibold text-slate-900 mb-1">Recurring Commitments</h3>
        <p className="text-xs text-slate-500 py-6 text-center">
          No recurring payments or subscriptions detected yet. Regular monthly payments will appear here automatically.
        </p>
      </div>
    );
  }

  const { items, totalMonthlyRecurring, recurringPercentageOfExpenses } = data;

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Recurring Commitments</h3>
          <p className="text-xs text-slate-500">
            Detected subscriptions, bills, and periodic obligations
          </p>
        </div>
        <div className="text-right">
          <span className="text-xs text-slate-400 uppercase tracking-wider font-semibold block">
            Est. Monthly Cost
          </span>
          <div className="flex items-baseline justify-end gap-1.5">
            <span className="text-lg font-bold text-slate-900">
              {formatCurrency(totalMonthlyRecurring)}
            </span>
            {recurringPercentageOfExpenses > 0 && (
              <span className="text-xs text-slate-500 font-medium">
                ({recurringPercentageOfExpenses}% of spend)
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="divide-y divide-slate-100">
        {items.map((item) => (
          <div key={item.id} className="py-3.5 first:pt-0 last:pb-0 flex items-center justify-between gap-4">
            <div className="min-w-0">
              <div className="flex items-center gap-2 mb-0.5">
                <span className="text-sm font-semibold text-slate-900 truncate">
                  {item.title}
                </span>
                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 border border-slate-200">
                  {item.cadence}
                </span>
                {item.isConfirmed ? (
                  <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                    ✓ Confirmed
                  </span>
                ) : (
                  <span
                    className={`text-[10px] font-medium px-2 py-0.5 rounded-full border ${
                      item.confidence === "HIGH"
                        ? "bg-sky-50 text-sky-700 border-sky-200"
                        : "bg-slate-50 text-slate-600 border-slate-200"
                    }`}
                  >
                    {item.confidence} confidence
                  </span>
                )}
              </div>
              <p className="text-xs text-slate-500 line-clamp-1">{item.explanation}</p>
            </div>

            <div className="text-right flex-shrink-0">
              <div className="text-sm font-bold text-slate-900">
                {formatCurrency(item.amount)}
                <span className="text-[11px] font-normal text-slate-400">/mo</span>
              </div>
              <div className="text-[11px] text-slate-400">
                Latest: {formatDate(item.lastDate)}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
