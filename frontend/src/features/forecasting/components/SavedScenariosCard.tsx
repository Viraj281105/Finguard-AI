import { useCallback, useEffect, useState } from "react";
import { apiErrorMessage } from "../../../shared/api/client";
import { formatCurrency } from "../../../shared/utils/format";
import { forecastAPI } from "../api";
import { signed, tidyMoney } from "../format";
import type { SavedScenarioDetail, SavedScenarioRerun, SavedScenarioSummary } from "../types";
import ScenarioComparison, { type ComparisonColumn } from "./ScenarioComparison";

const MAX_COMPARE = 3;

const total = (d: SavedScenarioDetail, which: "baseline" | "scenario") => d.snapshot[which];

/**
 * Scenarios the user kept. Each one can be re-run on the current ledger (the stored snapshot is never
 * overwritten) and up to three with the same horizon can be compared side by side.
 */
export default function SavedScenariosCard({ refreshKey }: { refreshKey: number }) {
  const [items, setItems] = useState<SavedScenarioSummary[] | null>(null);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [details, setDetails] = useState<Record<string, SavedScenarioDetail>>({});
  const [rerun, setRerun] = useState<SavedScenarioRerun | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    forecastAPI
      .listScenarios()
      .then((r) => { setItems(r); setError(""); })
      .catch((e) => setError(apiErrorMessage(e, "Could not load your saved scenarios.")));
  }, []);

  useEffect(() => { load(); }, [load, refreshKey]);

  const horizon = selected.length ? items?.find((i) => i.id === selected[0])?.horizonMonths : undefined;

  const toggle = (id: string) => {
    if (selected.includes(id)) {
      setSelected((s) => s.filter((x) => x !== id));
      return;
    }
    setSelected((s) => [...s, id]);
    if (!details[id]) {
      forecastAPI
        .getScenario(id)
        .then((d) => setDetails((m) => ({ ...m, [id]: d })))
        .catch((e) => setError(apiErrorMessage(e, "Could not load that scenario.")));
    }
  };

  const doRerun = (id: string) => {
    setBusy(id);
    forecastAPI
      .rerunScenario(id)
      .then((r) => { setRerun(r); setError(""); })
      .catch((e) => {
        const msg = apiErrorMessage(e, "Could not re-run that scenario.");
        if (/unknown loan/i.test(msg)) {
          setError("This scenario references a loan that no longer exists in your account and cannot be re-run.");
        } else {
          setError(msg);
        }
      })
      .finally(() => setBusy(null));
  };

  const doDelete = (id: string) => {
    setBusy(id);
    forecastAPI
      .deleteScenario(id)
      .then(() => {
        setSelected((s) => s.filter((x) => x !== id));
        setRerun((r) => (r?.summary.id === id ? null : r));
        load();
      })
      .catch((e) => setError(apiErrorMessage(e, "Could not delete that scenario.")))
      .finally(() => setBusy(null));
  };

  const compared = selected.map((id) => details[id]).filter((d): d is SavedScenarioDetail => Boolean(d));
  const comparisonColumns: ComparisonColumn[] = compared.length
    ? [
        { key: "baseline", label: "Baseline", hint: `as saved for ${compared[0].summary.name}`, totals: total(compared[0], "baseline")! },
        ...compared.map((d) => ({
          key: d.summary.id,
          label: d.summary.name,
          totals: total(d, "scenario")!,
          interestSaved: d.snapshot.loanEffects?.length ? d.snapshot.loanEffects.reduce((a, e) => a + e.interestSaved, 0) : null,
        })),
      ]
    : [];

  const rerunColumns: ComparisonColumn[] = rerun && rerun.snapshot.baseline && rerun.snapshot.scenario && rerun.current.baseline && rerun.current.scenario
    ? [
        { key: "sb", label: "Baseline", hint: "when saved", totals: rerun.snapshot.baseline },
        { key: "ss", label: "Scenario", hint: "when saved", totals: rerun.snapshot.scenario },
        { key: "cb", label: "Baseline", hint: "now", totals: rerun.current.baseline },
        { key: "cs", label: "Scenario", hint: "now", totals: rerun.current.scenario },
      ]
    : [];

  return (
    <section aria-label="Saved scenarios" className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm space-y-4">
      <div>
        <h3 className="text-base font-semibold text-slate-900">Saved scenarios</h3>
        <p className="text-xs text-slate-500 mt-0.5">
          A saved scenario keeps your assumptions and the result calculated at the time. Re-running recalculates from your current records;
          it never overwrites what was saved. Choose up to {MAX_COMPARE} with the same horizon to compare.
        </p>
      </div>

      {error && <div role="alert" className="px-3 py-2 rounded-lg bg-rose-50 border border-rose-200 text-rose-800 text-xs">{error}</div>}

      {items === null && !error && <p role="status" className="text-xs text-slate-500">Loading saved scenarios…</p>}
      {items !== null && items.length === 0 && <p className="text-xs text-slate-500">Nothing saved yet. Build a scenario above and choose “Save scenario”.</p>}

      {items !== null && items.length > 0 && (
        <ul className="divide-y divide-slate-100">
          {items.map((s) => {
            const checked = selected.includes(s.id);
            const blocked = !checked && (selected.length >= MAX_COMPARE || (horizon !== undefined && s.horizonMonths !== horizon));
            return (
              <li key={s.id} className="py-2.5 flex flex-wrap items-center gap-x-4 gap-y-1.5">
                <label className="flex items-center gap-2 text-xs text-slate-800 min-w-0 flex-1">
                  <input type="checkbox" checked={checked} disabled={blocked} onChange={() => toggle(s.id)} aria-label={`Compare ${s.name}`} />
                  <span className="font-semibold truncate">{s.name}</span>
                </label>
                <span className="text-[11px] text-slate-500">{s.horizonMonths} month{s.horizonMonths === 1 ? "" : "s"}</span>
                <span className="text-[11px] text-slate-600 tabular-nums w-32">
                  {s.netChange === null ? "No income baseline" : `Net ${signed(s.netChange, formatCurrency)}`}
                </span>
                <button type="button" disabled={busy === s.id} onClick={() => doRerun(s.id)}
                  className="text-xs font-semibold text-sky-700 hover:text-sky-900 disabled:text-slate-400" aria-label={`Re-run ${s.name}`}>Re-run now</button>
                <button type="button" disabled={busy === s.id} onClick={() => doDelete(s.id)}
                  className="text-xs font-semibold text-rose-600 hover:text-rose-800 disabled:text-slate-400" aria-label={`Delete ${s.name}`}>Delete</button>
              </li>
            );
          })}
        </ul>
      )}

      {compared.length > 0 && (
        <div className="border-t border-slate-100 pt-3">
          <h4 className="text-xs font-semibold text-slate-800 mb-2">Comparison (as saved)</h4>
          <ScenarioComparison columns={comparisonColumns} caption="Saved scenarios compared with the baseline they were saved with" />
          {compared.length > 1 && compared.some((d) => d.snapshot.baseline?.net !== compared[0].snapshot.baseline?.net) && (
            <p className="text-[11px] text-amber-800 mt-2">These scenarios were saved on different data, so their baselines differ. Re-run each to compare on today’s records.</p>
          )}
        </div>
      )}

      {rerun && (
        <div className="border-t border-slate-100 pt-3 space-y-2" aria-label="Re-run result">
          <h4 className="text-xs font-semibold text-slate-800">Re-run of “{rerun.summary.name}”</h4>
          {rerun.unchanged ? (
            <p role="status" className="text-xs text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
              Same result as when it was saved: your records and the forecasting method have not changed it.
            </p>
          ) : (
            <p role="status" className="text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
              {rerun.methodVersionChanged
                ? `The forecasting method changed since this was saved (now ${rerun.currentMethodVersion}), so the figures can differ.`
                : "Your records have changed since this was saved, so the figures differ."}
            </p>
          )}
          {!rerun.current.available && <p className="text-xs text-slate-700">It cannot be calculated now: {rerun.current.unavailableReason}</p>}
          {rerunColumns.length > 0 && !rerun.unchanged && (
            <ScenarioComparison columns={rerunColumns} caption="The saved result compared with a fresh calculation on current records" />
          )}
          {rerun.current.available && rerun.current.explanations.length > 0 && (
            <ul className="list-disc pl-5 text-[11px] text-slate-600 space-y-0.5">
              {rerun.current.explanations.map((e) => <li key={e}>{tidyMoney(e)}</li>)}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
