import { formatDate } from "../../../shared/utils/format";
import type { DataQuality } from "../types";

interface DataQualityCardProps {
  data: DataQuality | null;
}

export default function DataQualityCard({ data }: DataQualityCardProps) {
  if (!data) return null;

  const {
    coverageFrom,
    coverageTo,
    totalDaysCovered,
    totalMonthsCovered,
    totalTransactions,
    uncategorizedCount,
    uncategorizedPercentage,
    missingMonths,
    hasSufficientHistory,
    caveats,
  } = data;

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Data Coverage & Diagnostics</h3>
          <p className="text-xs text-slate-500">
            Accounting for transaction boundaries, uncategorized volume, and data completeness
          </p>
        </div>
        <span
          className={`text-xs font-semibold px-2.5 py-1 rounded-full border ${
            hasSufficientHistory
              ? "bg-emerald-50 text-emerald-700 border-emerald-200"
              : "bg-amber-50 text-amber-700 border-amber-200"
          }`}
        >
          {hasSufficientHistory ? "✓ Multi-Month History" : "⚠️ Limited Timeline"}
        </span>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
        <div className="p-3 rounded-xl bg-slate-50 border border-slate-100">
          <span className="text-[11px] font-medium text-slate-400 block mb-0.5">Timeline Span</span>
          <span className="text-sm font-bold text-slate-800">
            {coverageFrom && coverageTo ? `${formatDate(coverageFrom)} – ${formatDate(coverageTo)}` : "None"}
          </span>
          <span className="text-[10px] text-slate-400 block mt-0.5">{totalDaysCovered} days span</span>
        </div>

        <div className="p-3 rounded-xl bg-slate-50 border border-slate-100">
          <span className="text-[11px] font-medium text-slate-400 block mb-0.5">Months Covered</span>
          <span className="text-sm font-bold text-slate-800">{totalMonthsCovered} Months</span>
          <span className="text-[10px] text-slate-400 block mt-0.5">
            {hasSufficientHistory ? "Sufficient for MoM" : "Need ≥ 3 for MoM"}
          </span>
        </div>

        <div className="p-3 rounded-xl bg-slate-50 border border-slate-100">
          <span className="text-[11px] font-medium text-slate-400 block mb-0.5">Total Ledger Rows</span>
          <span className="text-sm font-bold text-slate-800">{totalTransactions} Txns</span>
          <span className="text-[10px] text-slate-400 block mt-0.5">Persisted records</span>
        </div>

        <div className="p-3 rounded-xl bg-slate-50 border border-slate-100">
          <span className="text-[11px] font-medium text-slate-400 block mb-0.5">Uncategorized</span>
          <span className={`text-sm font-bold ${uncategorizedPercentage > 15 ? "text-amber-700" : "text-slate-800"}`}>
            {uncategorizedPercentage}%
          </span>
          <span className="text-[10px] text-slate-400 block mt-0.5">
            {uncategorizedCount} row{uncategorizedCount !== 1 ? "s" : ""} uncategorized
          </span>
        </div>
      </div>

      {(missingMonths ?? []).length > 0 && (
        <div className="mb-3 p-2.5 rounded-xl bg-amber-50/70 border border-amber-200/80 text-xs text-amber-800 flex items-center gap-2">
          <span>⚠️</span>
          <span>Missing statement imports for month(s): <strong>{(missingMonths ?? []).join(", ")}</strong></span>
        </div>
      )}

      {(caveats ?? []).length > 0 && (
        <div className="space-y-1 pt-2 border-t border-slate-100">
          {(caveats ?? []).map((c, i) => (
            <div key={i} className="text-[11px] text-slate-500 flex items-start gap-1.5">
              <span className="text-slate-400">•</span>
              <span>{c}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
