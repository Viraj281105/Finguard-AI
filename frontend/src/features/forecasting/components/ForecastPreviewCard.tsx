import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { formatCurrency } from "../../../shared/utils/format";
import { forecastAPI } from "../api";
import { monthLabel, signed } from "../format";
import type { CashFlowForecast } from "../types";

/**
 * Dashboard teaser for next month's projection. It renders nothing when there is no forecast (not enough
 * history, or the request failed) rather than a placeholder number, and says "projection" wherever a
 * figure appears.
 */
export default function ForecastPreviewCard() {
  const [forecast, setForecast] = useState<CashFlowForecast | null>(null);

  useEffect(() => {
    let mounted = true;
    forecastAPI
      .getCashFlow(1)
      .then((data) => {
        if (mounted) setForecast(data);
      })
      .catch(() => undefined);
    return () => {
      mounted = false;
    };
  }, []);

  const next = forecast && forecast.reliability !== "UNAVAILABLE" ? forecast.projected[0] : undefined;
  if (!forecast || !next) return null;

  return (
    <section aria-label="Next month projection" className="rounded-2xl bg-gradient-to-r from-slate-900 to-indigo-950 p-6 text-white shadow-md mb-8">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
        <div>
          <h3 className="text-base font-bold text-white tracking-tight">Next month: projection</h3>
          <p className="text-xs text-slate-300">
            {monthLabel(next.month)} · based on {forecast.history.monthsUsed} recorded months
            {forecast.reliability === "LIMITED" ? " (limited history)" : ""} · not a promise
          </p>
        </div>
        <Link to="/forecasting" className="px-3.5 py-1.5 rounded-xl text-xs font-semibold bg-white/10 hover:bg-white/20 text-white border border-white/20 transition-all">
          Explore forecast &amp; scenarios →
        </Link>
      </div>
      <dl className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-3 border-t border-white/10">
        <div>
          <dt className="text-[11px] text-slate-400 uppercase tracking-wider">Projected income</dt>
          <dd className="text-lg font-bold text-sky-300">{next.income ? formatCurrency(next.income.point) : "Not projected"}</dd>
        </div>
        <div>
          <dt className="text-[11px] text-slate-400 uppercase tracking-wider">Projected spending</dt>
          <dd className="text-lg font-bold text-slate-200">{formatCurrency(next.expenses.point)}</dd>
        </div>
        <div>
          <dt className="text-[11px] text-slate-400 uppercase tracking-wider">Projected net</dt>
          <dd className={`text-lg font-bold ${next.net ? (next.net.point >= 0 ? "text-emerald-400" : "text-rose-400") : "text-slate-400"}`}>
            {next.net ? signed(next.net.point, formatCurrency) : "Not projected"}
          </dd>
        </div>
      </dl>
    </section>
  );
}
