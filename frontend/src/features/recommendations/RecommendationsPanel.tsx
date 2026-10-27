import { useCallback } from "react";
import { Link } from "react-router-dom";
import { useResource } from "../../shared/hooks/useResource";
import { formatCurrency } from "../../shared/utils/format";
import { recommendationsAPI } from "./api";
import type { Recommendation, RecommendationEvidence, Severity } from "./types";

const SEVERITY: Record<Severity, { label: string; tone: string }> = {
  CRITICAL: { label: "Critical", tone: "bg-rose-50 text-rose-800 border-rose-200" },
  HIGH: { label: "High", tone: "bg-orange-50 text-orange-800 border-orange-200" },
  MEDIUM: { label: "Medium", tone: "bg-amber-50 text-amber-900 border-amber-200" },
  INFO: { label: "For your information", tone: "bg-sky-50 text-sky-800 border-sky-200" },
};

function evidenceValue(e: RecommendationEvidence): string {
  if (e.unit === "₹") return formatCurrency(e.value);
  if (e.unit === "%") return `${e.value}%`;
  return `${e.value} ${e.unit}`;
}

function Item({ rec }: { rec: Recommendation }) {
  const sev = SEVERITY[rec.severity];
  return (
    <li>
      <details className="group rounded-xl border border-slate-200 bg-white open:shadow-sm">
        <summary className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 cursor-pointer list-none">
          <span className={`text-[11px] font-semibold px-2.5 py-0.5 rounded-full border ${sev.tone}`}>{sev.label}</span>
          <span className="text-sm font-semibold text-slate-900 flex-1 min-w-0">{rec.title}</span>
          {rec.estimatedMonthlyImpact !== null && (
            <span className="text-[11px] text-slate-500">about {formatCurrency(rec.estimatedMonthlyImpact)} a month</span>
          )}
          <span aria-hidden="true" className="text-slate-400 text-xs group-open:rotate-90 transition-transform">▸</span>
        </summary>
        <div className="px-4 pb-4 space-y-3 text-xs text-slate-700">
          <p>{rec.explanation}</p>
          <p><strong className="text-slate-900">What you could do:</strong> {rec.suggestedAction}</p>
          <div>
            <h4 className="text-[11px] font-semibold text-slate-800 mb-1">Evidence</h4>
            <ul className="space-y-0.5" aria-label={`Evidence for ${rec.title}`}>
              {rec.evidence.map((e) => (
                <li key={e.metric} className="flex flex-wrap justify-between gap-2">
                  <span>{e.metric}</span>
                  <span className="tabular-nums font-semibold text-slate-900" title={`Reproduce with GET ${e.source}`}>{evidenceValue(e)}</span>
                </li>
              ))}
            </ul>
          </div>
          {rec.scenarioPreset && (
            <Link to="/forecasting" state={{ preset: rec.scenarioPreset }}
              className="inline-block font-semibold text-sky-700 hover:text-sky-900 border border-sky-200 rounded-lg px-3 py-1.5">
              Try this scenario: {rec.scenarioPreset.label}
            </Link>
          )}
        </div>
      </details>
    </li>
  );
}

/**
 * Dashboard panel of general-guidance recommendations. Everything shown comes from the backend; the panel states
 * plainly when nothing stands out, when there is too little data to judge, and which checks could not be made.
 */
export default function RecommendationsPanel() {
  const fetcher = useCallback(() => recommendationsAPI.get(), []);
  const { data, loading, error, reload } = useResource(fetcher, "Could not load recommendations.");

  return (
    <section aria-label="Recommendations" className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-xs mb-8">
      <div className="flex flex-wrap items-baseline justify-between gap-2 mb-1">
        <h3 className="text-base font-semibold text-slate-900">Recommendations</h3>
        {data && <span className="text-[11px] text-slate-500">Based on {data.period.from} to {data.period.to}</span>}
      </div>
      <p className="text-[11px] text-slate-500 mb-3" data-testid="recommendations-disclaimer">
        {data?.disclaimer ?? "General guidance, not financial advice."}
      </p>

      {loading && <p role="status" className="text-xs text-slate-500">Checking your records…</p>}
      {!loading && error && (
        <div role="alert" className="text-xs text-rose-800 bg-rose-50 border border-rose-200 rounded-lg px-3 py-2">
          {error} <button type="button" onClick={reload} className="font-semibold underline ml-2">Retry</button>
        </div>
      )}

      {data && (
        <>
          {data.recommendations.length > 0 && (
            <ul className="space-y-2" aria-label="Recommendations list">
              {data.recommendations.map((r) => <Item key={r.id} rec={r} />)}
            </ul>
          )}
          {data.recommendations.length === 0 && data.healthy && (
            <p role="status" className="text-xs text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-3">
              Nothing stands out. Based on your last {data.period.monthsWithData} months of records, none of the checks below was triggered.
            </p>
          )}
          {data.recommendations.length === 0 && !data.healthy && (
            <p role="status" className="text-xs text-slate-700 bg-slate-50 border border-slate-200 rounded-lg px-3 py-3">
              There is not enough recorded history to judge yet ({data.period.monthsWithData} of the last {data.period.months} months have records).
              Import more statements to get recommendations.
            </p>
          )}
          {data.notEvaluated.length > 0 && (
            <details className="mt-3">
              <summary className="text-[11px] font-semibold text-sky-700 cursor-pointer">What could not be checked ({data.notEvaluated.length})</summary>
              <ul className="mt-1 space-y-1 text-[11px] text-slate-600 list-disc pl-5">
                {data.notEvaluated.map((n) => <li key={n.id}><strong className="text-slate-800">{n.title}:</strong> {n.reason}</li>)}
              </ul>
            </details>
          )}
        </>
      )}
    </section>
  );
}
