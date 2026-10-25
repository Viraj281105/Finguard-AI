import { formatCurrency, formatDate } from "../../../shared/utils/format";
import type { UnusualTransaction } from "../types";

interface UnusualSpendingCardProps {
  outliers: UnusualTransaction[];
}

export default function UnusualSpendingCard({ outliers }: UnusualSpendingCardProps) {
  if (!outliers || outliers.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
        <h3 className="text-base font-semibold text-slate-900 mb-1">Attention Needed</h3>
        <p className="text-xs text-slate-500 py-6 text-center">
          No statistical anomalies or unusually large transactions detected in this period.
        </p>
      </div>
    );
  }

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Attention Needed</h3>
          <p className="text-xs text-slate-500">
            Unusually large transactions exceeding your typical spending benchmarks
          </p>
        </div>
        <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-rose-50 text-rose-700 border border-rose-200">
          {outliers.length} flagged
        </span>
      </div>

      <div className="divide-y divide-slate-100">
        {outliers.map((item) => (
          <div key={item.transactionId} className="py-3 first:pt-0 last:pb-0 flex items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-sm font-semibold text-slate-900 truncate">
                  {item.title}
                </span>
                <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 border border-slate-200">
                  {item.category}
                </span>
                <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-rose-100 text-rose-800">
                  {item.ratio}x Benchmark
                </span>
              </div>
              <p className="text-xs text-slate-600">{item.reason}</p>
              <span className="text-[11px] text-slate-400 mt-0.5 block">
                {formatDate(item.date)} · Benchmark: {formatCurrency(item.benchmarkAmount)}
              </span>
            </div>

            <div className="text-right flex-shrink-0">
              <span className="text-sm font-bold text-slate-900 block">
                {formatCurrency(item.amount)}
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
