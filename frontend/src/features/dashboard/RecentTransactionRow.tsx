import { categoryLabel } from "../../shared/categories";
import { formatCurrency, formatDate } from "../../shared/utils/format";
import type { Transaction } from "../transactions/types";

export default function RecentTransactionRow({ tx }: { tx: Transaction }) {
  const isIncome = tx.type === "INCOME";

  return (
    <div className="flex items-center justify-between py-3 border-b border-slate-100 last:border-0 hover:bg-slate-50/60 px-2 rounded-xl transition-colors">
      <div className="flex items-center gap-3 min-w-0">
        <div
          className={`w-8 h-8 rounded-xl flex items-center justify-center text-xs font-bold flex-shrink-0 ${
            isIncome
              ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
              : "bg-slate-100 text-slate-700 border border-slate-200"
          }`}
        >
          {isIncome ? "↑" : "↓"}
        </div>
        <div className="truncate">
          <p className="text-sm font-semibold text-slate-900 truncate">{tx.title}</p>
          <p className="text-xs text-slate-400">
            {formatDate(tx.date)} · {categoryLabel(tx.category)}
          </p>
        </div>
      </div>

      <span
        className={`text-sm font-bold flex-shrink-0 ml-4 ${
          isIncome ? "text-emerald-600" : "text-slate-800"
        }`}
      >
        {isIncome ? "+" : "-"}
        {formatCurrency(tx.amount)}
      </span>
    </div>
  );
}
