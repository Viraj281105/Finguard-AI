import { useEffect, useId, useRef, useState } from "react";
import { apiErrorMessage } from "../../../shared/api/client";
import { formatCurrency } from "../../../shared/utils/format";
import { loanAPI } from "../../loans/api";
import type { Loan } from "../../loans/types";
import { forecastAPI } from "../api";
import { monthLabel, shortMonth, signed, tidyMoney } from "../format";
import type {
  AddedRecurringExpense,
  AdjustmentType,
  CashFlowForecast,
  CategoryAdjustment,
  CategoryForecastResponse,
  LoanAction,
  LoanAdjustment,
  OneTimeItem,
  ScenarioRequest,
  ScenarioResult,
} from "../types";
import SavedScenariosCard from "./SavedScenariosCard";

type IncomeMode = "NONE" | AdjustmentType;
interface CategoryRow { key: number; category: string; type: AdjustmentType; value: string }
interface AddedRow { key: number; title: string; category: string; amount: string }
interface LoanRow { key: number; loanId: string; action: LoanAction; amount: string }
interface ItemRow { key: number; title: string; kind: "INCOME" | "EXPENSE"; month: string; amount: string }

const LOAN_ACTIONS: { value: LoanAction; label: string }[] = [
  { value: "CLOSE", label: "Close it (repay the whole balance in month 1)" },
  { value: "PREPAY", label: "Prepay an amount in month 1" },
  { value: "CHANGE_EMI", label: "Change the EMI" },
];

const DEBOUNCE_MS = 450;
const inputCls = "w-full rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-xs text-slate-900 focus:outline-none focus:ring-2 focus:ring-sky-500/40 focus:border-sky-500 disabled:bg-slate-100 disabled:text-slate-400";

const num = (s: string): number | null => {
  if (s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
};

const unit = (type: AdjustmentType) => (type === "PERCENTAGE" ? "%" : "₹/month");

function MonthlyNetBars({ result }: { result: ScenarioResult }) {
  const rows = result.months.filter((m) => m.baselineNet !== null && m.scenarioNet !== null);
  if (rows.length === 0) return null;
  const values = rows.flatMap((m) => [m.baselineNet as number, m.scenarioNet as number, 0]);
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const w = Math.max(360, rows.length * 70);
  const h = 130;
  const top = 10;
  const bottom = 24;
  const plot = h - top - bottom;
  const y = (v: number) => top + plot - ((v - min) / span) * plot;
  const group = (w - 20) / rows.length;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-auto max-w-2xl" role="img"
      aria-label={`Monthly net cash flow, baseline versus scenario, ${rows.length} months. Hypothetical.`}>
      <line x1="10" x2={w - 10} y1={y(0)} y2={y(0)} stroke="#cbd5e1" />
      {rows.map((m, i) => {
        const gx = 10 + i * group;
        const bar = (v: number, x: number, fill: string, stroke?: string) => (
          <rect x={x} y={Math.min(y(v), y(0))} width={Math.min(18, group / 2 - 4)} height={Math.max(2, Math.abs(y(0) - y(v)))} rx="2" fill={fill} stroke={stroke} strokeDasharray={stroke ? "3 2" : undefined} />
        );
        return (
          <g key={m.month}>
            {bar(m.baselineNet as number, gx + group / 2 - Math.min(18, group / 2 - 4) - 1, "#94a3b8")}
            {bar(m.scenarioNet as number, gx + group / 2 + 1, "#bae6fd", "#0369a1")}
            <text x={gx + group / 2} y={h - 8} textAnchor="middle" fontSize="10" fill="#475569">{shortMonth(m.month)}</text>
          </g>
        );
      })}
    </svg>
  );
}

/**
 * What-if explorer. Every change is sent to the backend (debounced), which recalculates from the
 * baseline projection; the browser does no money arithmetic. Results are labelled hypothetical and the
 * server never writes anything.
 */
export default function ScenarioSimulatorCard({
  cashFlow,
  categories,
  horizonMonths,
}: {
  cashFlow: CashFlowForecast;
  categories: CategoryForecastResponse;
  horizonMonths: number;
}) {
  const uid = useId();
  const [incomeMode, setIncomeMode] = useState<IncomeMode>("NONE");
  const [incomeValue, setIncomeValue] = useState("");
  const [catRows, setCatRows] = useState<CategoryRow[]>([]);
  const [added, setAdded] = useState<AddedRow[]>([]);
  const [removed, setRemoved] = useState<string[]>([]);
  const [loanRows, setLoanRows] = useState<LoanRow[]>([]);
  const [itemRows, setItemRows] = useState<ItemRow[]>([]);
  const [loans, setLoans] = useState<Loan[]>([]);
  const [saveName, setSaveName] = useState("");
  const [saveMessage, setSaveMessage] = useState("");
  const [saveError, setSaveError] = useState("");
  const [saving, setSaving] = useState(false);
  const [savedVersion, setSavedVersion] = useState(0);
  const [result, setResult] = useState<ScenarioResult | null>(null);
  const [error, setError] = useState("");
  const [settledFor, setSettledFor] = useState<string | null>(null);
  const seq = useRef(0);
  const nextKey = useRef(1);

  const request: ScenarioRequest = { horizonMonths };
  const inc = num(incomeValue);
  if (incomeMode !== "NONE" && inc !== null) request.incomeAdjustment = { type: incomeMode, value: inc };
  const cats: CategoryAdjustment[] = catRows.flatMap((r) => {
    const v = num(r.value);
    return r.category && v !== null ? [{ category: r.category, type: r.type, value: v }] : [];
  });
  if (cats.length) request.categoryAdjustments = cats;
  const adds: AddedRecurringExpense[] = added.flatMap((r) => {
    const v = num(r.amount);
    return r.title.trim() && v !== null && v > 0
      ? [{ title: r.title.trim(), monthlyAmount: v, ...(r.category ? { category: r.category } : {}) }]
      : [];
  });
  if (adds.length) request.addedRecurringExpenses = adds;
  if (removed.length) request.removedRecurringExpenseIds = removed;
  const loanAdj: LoanAdjustment[] = loanRows.flatMap((r): LoanAdjustment[] => {
    if (!r.loanId) return [];
    if (r.action === "CLOSE") return [{ loanId: r.loanId, action: r.action }];
    const v = num(r.amount);
    return v !== null && v > 0 ? [{ loanId: r.loanId, action: r.action, amount: v }] : [];
  });
  if (loanAdj.length) request.loanAdjustments = loanAdj;
  const items: OneTimeItem[] = itemRows.flatMap((r) => {
    const v = num(r.amount);
    const m = num(r.month);
    return r.title.trim() && v !== null && v > 0 && m !== null && Number.isInteger(m) && m >= 1 && m <= horizonMonths
      ? [{ title: r.title.trim(), kind: r.kind, month: m, amount: v }]
      : [];
  });
  if (items.length) request.oneTimeItems = items;
  const signature = JSON.stringify(request);

  useEffect(() => {
    const mine = ++seq.current;
    const timer = setTimeout(() => {
      forecastAPI
        .simulateScenario(JSON.parse(signature) as ScenarioRequest)
        .then((r) => {
          if (mine !== seq.current) return;
          setResult(r);
          setError("");
          setSettledFor(signature);
        })
        .catch((e) => {
          if (mine !== seq.current) return;
          setError(apiErrorMessage(e, "Could not calculate this scenario. Please try again."));
          setSettledFor(signature);
        });
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [signature]);
  const loading = settledFor !== signature;

  useEffect(() => {
    let live = true;
    loanAPI.getAll().then((l) => { if (live) setLoans(l); }).catch(() => { if (live) setLoans([]); });
    return () => { live = false; };
  }, []);
  const usableLoans = loans.filter((l) => l.active && l.startDate);

  const save = () => {
    const name = saveName.trim();
    if (!name) { setSaveError("Give the scenario a name before saving it."); setSaveMessage(""); return; }
    setSaving(true);
    forecastAPI
      .saveScenario({ ...(JSON.parse(signature) as ScenarioRequest), name })
      .then(() => { setSaveMessage(`Saved “${name}”.`); setSaveError(""); setSaveName(""); setSavedVersion((v) => v + 1); })
      .catch((e) => { setSaveError(apiErrorMessage(e, "Could not save this scenario.")); setSaveMessage(""); })
      .finally(() => setSaving(false));
  };

  const reset = () => {
    setIncomeMode("NONE"); setIncomeValue(""); setCatRows([]); setAdded([]); setRemoved([]); setLoanRows([]); setItemRows([]);
  };
  const toggleRemoved = (id: string) => setRemoved((r) => (r.includes(id) ? r.filter((x) => x !== id) : [...r, id]));

  const baselineMissing = cashFlow.reliability === "UNAVAILABLE";
  const spendingCats = categories.categories;

  return (
    <div className="space-y-5">
      <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Scenario simulator</h3>
            <p className="text-xs text-slate-500 mt-0.5">Change an assumption and compare it with your projected baseline over {horizonMonths} month{horizonMonths === 1 ? "" : "s"}.</p>
          </div>
          <span className="text-[11px] font-semibold px-2.5 py-1 rounded-full bg-violet-50 text-violet-700 border border-violet-200">Hypothetical: changes no real records</span>
        </div>

        {baselineMissing ? (
          <p className="mt-4 text-xs text-slate-700 bg-slate-50 border border-slate-200 rounded-lg px-3 py-3">
            Scenarios build on a baseline projection, and there is not enough history for one yet.
          </p>
        ) : (
          <div className="mt-4 grid lg:grid-cols-2 gap-5">
            <fieldset className="space-y-2">
              <legend className="text-xs font-semibold text-slate-800 mb-1">Monthly income</legend>
              <div className="flex gap-2">
                <select aria-label="Income change type" className={inputCls} value={incomeMode} disabled={!cashFlow.incomeAvailable}
                  onChange={(e) => setIncomeMode(e.target.value as IncomeMode)}>
                  <option value="NONE">No change</option>
                  <option value="PERCENTAGE">Change by a percentage</option>
                  <option value="FIXED_AMOUNT">Change by an amount a month</option>
                </select>
                <input aria-label="Income change value" type="number" step="any" className={inputCls} placeholder={incomeMode === "FIXED_AMOUNT" ? "₹ per month, e.g. -5000" : "% e.g. 10"}
                  value={incomeValue} disabled={incomeMode === "NONE" || !cashFlow.incomeAvailable} onChange={(e) => setIncomeValue(e.target.value)} />
              </div>
              {!cashFlow.incomeAvailable && <p className="text-[11px] text-slate-500">No regular income was found in your history, so income cannot be adjusted.</p>}
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="text-xs font-semibold text-slate-800 mb-1">Change spending in a category</legend>
              {catRows.map((r) => (
                <div key={r.key} className="flex gap-2 items-center">
                  <select aria-label="Category" className={inputCls} value={r.category}
                    onChange={(e) => setCatRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, category: e.target.value } : x)))}>
                    <option value="">Choose…</option>
                    {spendingCats.map((c) => <option key={c.category} value={c.category}>{c.label}</option>)}
                  </select>
                  <select aria-label="Category change type" className={inputCls} value={r.type}
                    onChange={(e) => setCatRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, type: e.target.value as AdjustmentType } : x)))}>
                    <option value="PERCENTAGE">%</option>
                    <option value="FIXED_AMOUNT">₹/month</option>
                  </select>
                  <input aria-label="Category change value" type="number" step="any" className={inputCls} placeholder={r.type === "PERCENTAGE" ? "-20" : "-2000"}
                    value={r.value} onChange={(e) => setCatRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, value: e.target.value } : x)))} />
                  <button type="button" aria-label="Remove category change" className="text-slate-400 hover:text-rose-600 px-1"
                    onClick={() => setCatRows((rows) => rows.filter((x) => x.key !== r.key))}>✕</button>
                </div>
              ))}
              <button type="button" disabled={spendingCats.length === 0} className="text-xs font-semibold text-sky-700 hover:text-sky-900 disabled:text-slate-400"
                onClick={() => setCatRows((rows) => [...rows, { key: nextKey.current++, category: "", type: "PERCENTAGE", value: "" }])}>
                + Add a category change
              </button>
              {spendingCats.length === 0 && <p className="text-[11px] text-slate-500">No category has enough history to adjust yet.</p>}
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="text-xs font-semibold text-slate-800 mb-1">Assume a new recurring expense</legend>
              {added.map((r) => (
                <div key={r.key} className="flex gap-2 items-center">
                  <input aria-label="New expense name" className={inputCls} placeholder="e.g. Gym" value={r.title}
                    onChange={(e) => setAdded((rows) => rows.map((x) => (x.key === r.key ? { ...x, title: e.target.value } : x)))} />
                  <input aria-label="New expense amount per month" type="number" min="0" step="any" className={inputCls} placeholder="₹/month" value={r.amount}
                    onChange={(e) => setAdded((rows) => rows.map((x) => (x.key === r.key ? { ...x, amount: e.target.value } : x)))} />
                  <select aria-label="New expense category" className={inputCls} value={r.category}
                    onChange={(e) => setAdded((rows) => rows.map((x) => (x.key === r.key ? { ...x, category: e.target.value } : x)))}>
                    <option value="">No category</option>
                    {spendingCats.map((c) => <option key={c.category} value={c.category}>{c.label}</option>)}
                  </select>
                  <button type="button" aria-label="Remove new expense" className="text-slate-400 hover:text-rose-600 px-1"
                    onClick={() => setAdded((rows) => rows.filter((x) => x.key !== r.key))}>✕</button>
                </div>
              ))}
              <button type="button" className="text-xs font-semibold text-sky-700 hover:text-sky-900"
                onClick={() => setAdded((rows) => [...rows, { key: nextKey.current++, title: "", category: "", amount: "" }])}>
                + Add an expense
              </button>
            </fieldset>

            <fieldset className="space-y-1.5">
              <legend className="text-xs font-semibold text-slate-800 mb-1">Stop a detected recurring payment</legend>
              {cashFlow.recurring.length === 0 ? (
                <p className="text-[11px] text-slate-500">No active recurring payments were detected.</p>
              ) : (
                cashFlow.recurring.map((r) => (
                  <label key={r.id} htmlFor={`${uid}-${r.id}`} className="flex items-center gap-2 text-xs text-slate-700">
                    <input id={`${uid}-${r.id}`} type="checkbox" checked={removed.includes(r.id)} onChange={() => toggleRemoved(r.id)} />
                    <span>{r.title} · {formatCurrency(r.monthlyAmount)}/month{r.userConfirmed ? " · confirmed" : " · detected"}</span>
                  </label>
                ))
              )}
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="text-xs font-semibold text-slate-800 mb-1">Change a loan</legend>
              {loanRows.map((r) => (
                <div key={r.key} className="flex flex-wrap gap-2 items-center">
                  <select aria-label="Loan" className={inputCls} value={r.loanId}
                    onChange={(e) => setLoanRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, loanId: e.target.value } : x)))}>
                    <option value="">Choose a loan…</option>
                    {usableLoans.map((l) => <option key={l.id} value={l.id}>{l.name} · EMI {formatCurrency(l.emiAmount)}</option>)}
                  </select>
                  <select aria-label="Loan action" className={inputCls} value={r.action}
                    onChange={(e) => setLoanRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, action: e.target.value as LoanAction } : x)))}>
                    {LOAN_ACTIONS.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
                  </select>
                  {r.action !== "CLOSE" && (
                    <input aria-label={r.action === "PREPAY" ? "Prepayment amount" : "New EMI"} type="number" min="0" step="any" className={inputCls}
                      placeholder={r.action === "PREPAY" ? "₹ extra, once" : "₹ new EMI"} value={r.amount}
                      onChange={(e) => setLoanRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, amount: e.target.value } : x)))} />
                  )}
                  <button type="button" aria-label="Remove loan change" className="text-slate-400 hover:text-rose-600 px-1"
                    onClick={() => setLoanRows((rows) => rows.filter((x) => x.key !== r.key))}>✕</button>
                </div>
              ))}
              <button type="button" disabled={usableLoans.length === 0} className="text-xs font-semibold text-sky-700 hover:text-sky-900 disabled:text-slate-400"
                onClick={() => setLoanRows((rows) => [...rows, { key: nextKey.current++, loanId: "", action: "PREPAY", amount: "" }])}>
                + Add a loan change
              </button>
              {usableLoans.length === 0 && (
                <p className="text-[11px] text-slate-500">Add an active loan with a start date on the Loans page to try closing, prepaying or re-pricing it.</p>
              )}
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="text-xs font-semibold text-slate-800 mb-1">One-time income or expense</legend>
              {itemRows.map((r) => (
                <div key={r.key} className="flex flex-wrap gap-2 items-center">
                  <input aria-label="One-time name" className={inputCls} placeholder="e.g. New laptop" value={r.title}
                    onChange={(e) => setItemRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, title: e.target.value } : x)))} />
                  <select aria-label="One-time kind" className={inputCls} value={r.kind}
                    onChange={(e) => setItemRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, kind: e.target.value as "INCOME" | "EXPENSE" } : x)))}>
                    <option value="EXPENSE">Expense</option>
                    <option value="INCOME" disabled={!cashFlow.incomeAvailable}>Income</option>
                  </select>
                  <select aria-label="One-time month" className={inputCls} value={r.month}
                    onChange={(e) => setItemRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, month: e.target.value } : x)))}>
                    {Array.from({ length: horizonMonths }, (_, i) => <option key={i + 1} value={String(i + 1)}>Month {i + 1}</option>)}
                  </select>
                  <input aria-label="One-time amount" type="number" min="0" step="any" className={inputCls} placeholder="₹" value={r.amount}
                    onChange={(e) => setItemRows((rows) => rows.map((x) => (x.key === r.key ? { ...x, amount: e.target.value } : x)))} />
                  <button type="button" aria-label="Remove one-time item" className="text-slate-400 hover:text-rose-600 px-1"
                    onClick={() => setItemRows((rows) => rows.filter((x) => x.key !== r.key))}>✕</button>
                </div>
              ))}
              <button type="button" className="text-xs font-semibold text-sky-700 hover:text-sky-900"
                onClick={() => setItemRows((rows) => [...rows, { key: nextKey.current++, title: "", kind: "EXPENSE", month: "1", amount: "" }])}>
                + Add a one-time item
              </button>
            </fieldset>
          </div>
        )}

        {!baselineMissing && (
          <div className="mt-4 flex items-center gap-3">
            <button type="button" onClick={reset} className="text-xs font-semibold text-slate-600 hover:text-slate-900 border border-slate-200 rounded-lg px-3 py-1.5">Reset all assumptions</button>
            {loading && <span role="status" className="text-xs text-slate-500">Recalculating…</span>}
          </div>
        )}
      </div>

      {error && <div role="alert" className="px-4 py-3 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-xs">{error}</div>}

      {result && result.available && result.baseline && result.scenario && result.delta && (
        <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm space-y-4" aria-label="Scenario results">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-base font-semibold text-slate-900">Baseline vs scenario · {result.horizonMonths} month{result.horizonMonths === 1 ? "" : "s"}</h3>
            <span className="text-[11px] text-violet-700 font-semibold">Hypothetical</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left">
              <caption className="sr-only">Baseline compared with the scenario over the horizon</caption>
              <thead>
                <tr className="text-slate-500 border-b border-slate-100">
                  <th scope="col" className="py-2 pr-3 font-medium">Over the horizon</th>
                  <th scope="col" className="py-2 pr-3 font-medium text-right">Baseline</th>
                  <th scope="col" className="py-2 pr-3 font-medium text-right">Scenario</th>
                  <th scope="col" className="py-2 font-medium text-right">Change</th>
                </tr>
              </thead>
              <tbody className="tabular-nums">
                {([
                  ["Income", result.baseline.income, result.scenario.income, result.delta.income],
                  ["Spending", result.baseline.expenses, result.scenario.expenses, result.delta.expenses],
                  ["Net cash flow", result.baseline.net, result.scenario.net, result.delta.net],
                ] as const).map(([label, b, s, d]) => (
                  <tr key={label} className="border-b border-slate-50">
                    <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">{label}</th>
                    <td className="py-2 pr-3 text-right">{b === null ? "—" : formatCurrency(b)}</td>
                    <td className="py-2 pr-3 text-right font-semibold">{s === null ? "—" : formatCurrency(s)}</td>
                    <td className={`py-2 text-right font-semibold ${d === null ? "text-slate-400" : d === 0 ? "text-slate-500" : (label === "Spending" ? d < 0 : d > 0) ? "text-emerald-600" : "text-rose-600"}`}>
                      {d === null ? "—" : signed(d, formatCurrency)}
                    </td>
                  </tr>
                ))}
                {result.baseline.savingsRatePercent !== null && result.scenario.savingsRatePercent !== null && (
                  <tr>
                    <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">Savings rate</th>
                    <td className="py-2 pr-3 text-right">{result.baseline.savingsRatePercent.toFixed(1)}%</td>
                    <td className="py-2 pr-3 text-right font-semibold">{result.scenario.savingsRatePercent.toFixed(1)}%</td>
                    <td className="py-2 text-right font-semibold text-slate-700">
                      {result.delta.savingsRatePoints === null ? "—" : `${result.delta.savingsRatePoints > 0 ? "+" : ""}${result.delta.savingsRatePoints.toFixed(1)} pts`}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-800 mb-1">Monthly net cash flow</h4>
            <MonthlyNetBars result={result} />
            <p className="text-[11px] text-slate-500">Grey: baseline. Light blue with dashes: scenario. Months: {result.months.map((m) => monthLabel(m.month)).join(", ")}.</p>
          </div>

          {result.categories.length > 0 && (
            <ul className="text-xs text-slate-700 space-y-1" aria-label="Category effects">
              {result.categories.map((c) => (
                <li key={c.category}>
                  <strong className="text-slate-900">{c.label}</strong>: {formatCurrency(c.baselineMonthly)} → {formatCurrency(c.scenarioMonthly)} a month ({signed(c.deltaMonthly, formatCurrency)}) · {c.note}
                </li>
              ))}
            </ul>
          )}
          {result.loanEffects?.length > 0 && (
            <div>
              <h4 className="text-xs font-semibold text-slate-800 mb-1">Loan effects</h4>
              <div className="overflow-x-auto">
                <table className="w-full text-xs text-left">
                  <caption className="sr-only">What each loan change does, before and after</caption>
                  <thead>
                    <tr className="text-slate-500 border-b border-slate-100">
                      <th scope="col" className="py-2 pr-3 font-medium">Loan</th>
                      <th scope="col" className="py-2 pr-3 font-medium text-right">Estimated balance</th>
                      <th scope="col" className="py-2 pr-3 font-medium text-right">Months left</th>
                      <th scope="col" className="py-2 pr-3 font-medium text-right">Cash out in horizon</th>
                      <th scope="col" className="py-2 font-medium text-right">Interest saved (whole loan)</th>
                    </tr>
                  </thead>
                  <tbody className="tabular-nums">
                    {result.loanEffects.map((e) => (
                      <tr key={e.loanId} className="border-b border-slate-50">
                        <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">{e.loanName}</th>
                        <td className="py-2 pr-3 text-right">{formatCurrency(e.estimatedOutstandingBalance)}</td>
                        <td className="py-2 pr-3 text-right">{e.baselineMonthsToPayoff} → {e.scenarioMonthsToPayoff}</td>
                        <td className="py-2 pr-3 text-right">{signed(e.cashOutInHorizonChange, formatCurrency)}</td>
                        <td className={`py-2 text-right font-semibold ${e.interestSaved >= 0 ? "text-emerald-600" : "text-rose-600"}`}>{signed(e.interestSaved, formatCurrency)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="text-[11px] text-slate-500 mt-1">Balances are estimated from the loan’s start date, EMI and rate; they are not a lender statement.</p>
            </div>
          )}
          {result.warnings.length > 0 && (
            <ul className="space-y-1" aria-label="Scenario warnings">
              {result.warnings.map((w) => <li key={w} className="text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-lg px-3 py-1.5">{tidyMoney(w)}</li>)}
            </ul>
          )}
          <ul className="list-disc pl-5 text-[11px] text-slate-600 space-y-0.5">
            {result.explanations.map((e) => <li key={e}>{tidyMoney(e)}</li>)}
          </ul>
          <p className="text-[11px] text-slate-500 border-t border-slate-100 pt-3">{result.disclaimer} No uncertainty band is given for scenario figures.</p>
          <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
            <label htmlFor={`${uid}-save-name`} className="text-xs font-semibold text-slate-800">Keep this scenario</label>
            <input id={`${uid}-save-name`} aria-label="Scenario name" maxLength={80} className={`${inputCls} max-w-xs`} placeholder="Name, e.g. Prepay car loan"
              value={saveName} onChange={(e) => setSaveName(e.target.value)} />
            <button type="button" disabled={saving || loading} onClick={save}
              className="text-xs font-semibold text-white bg-sky-700 hover:bg-sky-800 disabled:bg-slate-300 rounded-lg px-3 py-1.5">Save scenario</button>
            {saveMessage && <span role="status" className="text-xs text-emerald-700">{saveMessage}</span>}
            {saveError && <span role="alert" className="text-xs text-rose-700">{saveError}</span>}
          </div>
        </div>
      )}

      {result && !result.available && (
        <div role="status" className="rounded-2xl bg-slate-50 border border-slate-200 p-5 text-xs text-slate-700">
          No scenario can be calculated: {result.unavailableReason}
        </div>
      )}
      {unitHint(incomeMode)}
      <SavedScenariosCard refreshKey={savedVersion} />
    </div>
  );
}

function unitHint(mode: IncomeMode) {
  return mode === "NONE" ? null : (
    <p className="text-[11px] text-slate-500">Income change unit: {unit(mode)}. Percentages must be between -100 and 500.</p>
  );
}
