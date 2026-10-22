import { categoryLabel } from "../../shared/categories";
import { formatCurrency } from "../../shared/utils/format";
import type { Transaction } from "../transactions/types";

export default function RecentTransactionRow({ tx }: { tx: Transaction }) {
  const isIncome = tx.type === "INCOME";
  return (
    <div className="flex items-center justify-between py-3 border-b border-white/[0.04] last:border-0 group">
      <div className="flex items-center gap-3">
        <div
          className={`w-8 h-8 rounded-xl flex items-center justify-center text-sm
          ${isIncome ? "bg-emerald-500/10 text-emerald-400" : "bg-rose-500/10 text-rose-400"}`}
        >
          {isIncome ? "↑" : "↓"}
        </div>
        <div>
          <p className="text-sm font-medium text-white/90">{tx.title}</p>
          <p className="text-xs text-white/30">{categoryLabel(tx.category)}</p>
        </div>
      </div>
      <span className={`text-sm font-semibold ${isIncome ? "text-emerald-400" : "text-rose-400"}`}>
        {isIncome ? "+" : "-"}
        {formatCurrency(tx.amount)}
      </span>
    </div>
  );
}
