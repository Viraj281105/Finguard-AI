import { useState } from "react";
import type { FinancialInsight } from "../types";

interface InsightsFeedProps {
  insights: FinancialInsight[];
}

export default function InsightsFeed({ insights }: InsightsFeedProps) {
  const [filter, setFilter] = useState<string>("ALL");

  if (!insights || insights.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
        <h3 className="text-base font-semibold text-slate-900 mb-1">Financial Intelligence</h3>
        <p className="text-xs text-slate-500 py-6 text-center">
          No automated observations generated for this period yet.
        </p>
      </div>
    );
  }

  const filtered = filter === "ALL" ? insights : insights.filter((i) => i.type === filter);

  const getBadgeStyle = (type: string) => {
    switch (type) {
      case "ALERT":
        return "bg-rose-50 text-rose-700 border-rose-200";
      case "TREND":
        return "bg-sky-50 text-sky-700 border-sky-200";
      case "POSITIVE":
        return "bg-emerald-50 text-emerald-700 border-emerald-200";
      default:
        return "bg-slate-50 text-slate-700 border-slate-200";
    }
  };

  const getTypeIcon = (type: string) => {
    switch (type) {
      case "ALERT":
        return "⚠️";
      case "TREND":
        return "📈";
      case "POSITIVE":
        return "✨";
      default:
        return "💡";
    }
  };

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Explainable Observations</h3>
          <p className="text-xs text-slate-500">
            Data-backed findings derived from your verified ledger calculations
          </p>
        </div>

        {/* Filter Pills */}
        <div className="flex items-center gap-1.5 bg-slate-100 p-1 rounded-xl text-xs">
          {["ALL", "ALERT", "TREND", "POSITIVE"].map((t) => (
            <button
              key={t}
              onClick={() => setFilter(t)}
              className={`px-3 py-1 rounded-lg font-medium transition-colors ${
                filter === t
                  ? "bg-white text-slate-900 shadow-xs"
                  : "text-slate-600 hover:text-slate-900"
              }`}
            >
              {t === "ALL" ? "All Insights" : t.charAt(0) + t.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-3">
        {filtered.map((item) => (
          <div
            key={item.id}
            className="p-4 rounded-xl border border-slate-200/70 bg-slate-50/50 hover:bg-slate-50 transition-colors"
          >
            <div className="flex items-center justify-between gap-2 mb-1.5">
              <div className="flex items-center gap-2">
                <span>{getTypeIcon(item.type)}</span>
                <h4 className="text-sm font-semibold text-slate-900">{item.title}</h4>
              </div>
              <span className={`text-[10px] font-semibold uppercase px-2 py-0.5 rounded-full border ${getBadgeStyle(item.type)}`}>
                {item.type}
              </span>
            </div>

            <p className="text-xs text-slate-700 mb-2 leading-relaxed">{item.message}</p>

            <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-slate-200/50 text-[11px] text-slate-500">
              <span className="font-medium text-slate-600">Rule: {item.ruleTriggered}</span>
              {item.confidenceCaveat && (
                <span className="text-slate-400 italic">{item.confidenceCaveat}</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
