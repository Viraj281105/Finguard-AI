import { useCallback, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { clearToken } from "../../shared/auth/tokenStorage";
import { useResource } from "../../shared/hooks/useResource";
import { formatCurrency } from "../../shared/utils/format";
import { authAPI } from "../auth/api";
import { forecastAPI } from "./api";
import BacktestCard from "./components/BacktestCard";
import BudgetOutlookCard from "./components/BudgetOutlookCard";
import CashFlowForecastChart from "./components/CashFlowForecastChart";
import CategoryForecastCard from "./components/CategoryForecastCard";
import ForecastNotes from "./components/ForecastNotes";
import ForecastSummaryStrip from "./components/ForecastSummaryStrip";
import SavingsOutlookCard from "./components/SavingsOutlookCard";
import ScenarioSimulatorCard from "./components/ScenarioSimulatorCard";

const NAV_LINKS = [
  { label: "Dashboard", to: "/dashboard" },
  { label: "Transactions", to: "/transactions" },
  { label: "Budgets & Goals", to: "/budgets" },
  { label: "Forecasting", to: "/forecasting", active: true },
  { label: "Investments", to: "/investments" },
  { label: "Loans", to: "/loans" },
];

const HORIZONS = [1, 3, 6, 12];

const TABS = [
  { id: "overview", label: "Cash flow" },
  { id: "categories", label: "Spending by category" },
  { id: "budgets", label: "Budget outlook" },
  { id: "savings", label: "Savings outlook" },
  { id: "scenarios", label: "Scenarios" },
  { id: "evaluation", label: "How reliable is this?" },
] as const;
type TabId = (typeof TABS)[number]["id"];

const SECTION_ERROR = (what: string) => `Could not load ${what}. The rest of the page is unaffected; please try again.`;

/** One section's loading / error handling, so a failing endpoint never blanks the whole page. */
function Section<T>({
  state,
  what,
  children,
}: {
  state: { data: T | null; loading: boolean; error: string; reload: () => void };
  what: string;
  children: (data: T) => ReactNode;
}) {
  if (state.data) return <>{children(state.data)}</>;
  if (state.error) {
    return (
      <div role="alert" className="rounded-2xl bg-rose-50 border border-rose-200 px-5 py-4 text-sm text-rose-800">
        {state.error}
        <button type="button" onClick={state.reload} className="ml-3 font-semibold underline">Retry</button>
      </div>
    );
  }
  return (
    <div role="status" className="rounded-2xl bg-white border border-slate-200/80 px-5 py-10 text-center text-xs text-slate-500">
      Loading {what}…
    </div>
  );
}

export default function ForecastingPage() {
  const navigate = useNavigate();
  const [horizon, setHorizon] = useState(3);
  const [tab, setTab] = useState<TabId>("overview");

  const fetchCash = useCallback(() => forecastAPI.getCashFlow(horizon), [horizon]);
  const fetchCategories = useCallback(() => forecastAPI.getCategories(horizon), [horizon]);
  const fetchSavings = useCallback(() => forecastAPI.getSavings(horizon), [horizon]);
  const fetchBudget = useCallback(() => forecastAPI.getBudgetOutlook(), []);
  const fetchBacktest = useCallback(() => forecastAPI.getBacktest(), []);

  const cash = useResource(fetchCash, SECTION_ERROR("the cash-flow forecast"));
  const categories = useResource(fetchCategories, SECTION_ERROR("the category forecast"));
  const savings = useResource(fetchSavings, SECTION_ERROR("the savings outlook"));
  const budget = useResource(fetchBudget, SECTION_ERROR("the budget outlook"));
  const backtest = useResource(fetchBacktest, SECTION_ERROR("the evaluation"));

  const updating = [cash.data, categories.data, savings.data].some((d) => d !== null && d.horizonMonths !== horizon);

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } finally {
      clearToken();
      navigate("/login");
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 font-sans antialiased">
      <nav className="sticky top-0 z-50 border-b border-slate-200/80 bg-white/90 backdrop-blur-md px-4 sm:px-6 py-3.5">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <span className="text-xl" aria-hidden="true">🛡️</span>
            <span className="font-bold text-lg tracking-tight text-slate-900">
              Fin<span className="text-sky-600">Guard</span>
            </span>
          </div>
          <div className="flex flex-wrap items-center gap-1 sm:gap-2">
            {NAV_LINKS.map((link) => (
              <Link
                key={link.to}
                to={link.to}
                aria-current={link.active ? "page" : undefined}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  link.active ? "bg-slate-100 text-slate-900 font-semibold" : "text-slate-600 hover:text-slate-900 hover:bg-slate-50"
                }`}
              >
                {link.label}
              </Link>
            ))}
            <button type="button" onClick={handleLogout}
              className="ml-2 px-3 py-1.5 text-xs font-medium text-slate-500 hover:text-rose-600 rounded-lg hover:bg-rose-50 transition-colors">
              Sign out
            </button>
          </div>
        </div>
      </nav>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-6 space-y-5">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-900">Forecast</h1>
            <p className="text-xs text-slate-500 mt-1 max-w-2xl">
              Projections from your own recorded transactions: simple statistics, no machine learning. They show where your
              current habits point, not what will happen, and they never invent numbers when the history is too thin.
            </p>
          </div>
          <div role="group" aria-label="Forecast horizon" className="flex items-center gap-1 bg-white p-1 rounded-xl border border-slate-200/80 shadow-sm">
            <span className="text-[11px] font-semibold text-slate-500 px-2 uppercase tracking-wider">Look ahead</span>
            {HORIZONS.map((m) => (
              <button key={m} type="button" aria-pressed={horizon === m} onClick={() => setHorizon(m)}
                className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all ${
                  horizon === m ? "bg-slate-900 text-white shadow-sm" : "text-slate-600 hover:text-slate-900 hover:bg-slate-100"
                }`}>
                {m} month{m === 1 ? "" : "s"}
              </button>
            ))}
          </div>
        </div>
        {updating && <p role="status" className="text-xs text-slate-500">Updating for {horizon} month{horizon === 1 ? "" : "s"}…</p>}

        <div role="tablist" aria-label="Forecast sections" className="flex items-center gap-1.5 border-b border-slate-200/80 pb-2 overflow-x-auto text-xs font-semibold">
          {TABS.map((t) => (
            <button key={t.id} type="button" role="tab" id={`tab-${t.id}`} aria-selected={tab === t.id} aria-controls={`panel-${t.id}`}
              onClick={() => setTab(t.id)}
              className={`px-3.5 py-1.5 rounded-xl whitespace-nowrap transition-all ${
                tab === t.id ? "bg-slate-900 text-white shadow-sm" : "text-slate-600 hover:text-slate-900 hover:bg-slate-100"
              }`}>
              {t.label}
            </button>
          ))}
        </div>

        <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="space-y-5">
          {tab === "overview" && (
            <Section state={cash} what="the cash-flow forecast">
              {(d) => (
                <>
                  <ForecastNotes reliability={d.reliability} method={d.method} algorithmVersion={d.algorithmVersion} history={d.history}
                    horizonMonths={d.horizonMonths} warnings={d.warnings} assumptions={d.assumptions} intervalNote={d.intervalNote} />
                  {d.reliability === "UNAVAILABLE" && (
                    <div className="rounded-2xl bg-amber-50/70 border border-amber-200 p-6 text-center">
                      <h3 className="text-base font-bold text-amber-950">Not enough history to project</h3>
                      <p className="text-xs text-amber-900 mt-1.5 max-w-xl mx-auto">
                        A projection needs at least {d.history.minMonthsLimited} complete calendar months with income or spending. Until then
                        FinGuard shows what has been recorded and projects nothing, rather than guessing.
                      </p>
                      <Link to="/dashboard" className="inline-block mt-3 px-4 py-2 bg-amber-600 text-white text-xs font-semibold rounded-xl hover:bg-amber-700">
                        Import a bank statement
                      </Link>
                    </div>
                  )}
                  <ForecastSummaryStrip data={d} />
                  <CashFlowForecastChart data={d} />
                  {d.recurring.length > 0 && (
                    <section aria-label="Recurring payments in the forecast" className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
                      <h3 className="text-sm font-semibold text-slate-900">Recurring payments behind the spending forecast</h3>
                      <p className="text-xs text-slate-500 mt-0.5">
                        Detected patterns, not confirmed subscriptions unless marked. They are already part of your recorded spending, so they
                        are not added a second time; they only stop the projection from falling below them.
                      </p>
                      <ul className="mt-2 divide-y divide-slate-100 text-xs">
                        {d.recurring.map((r) => (
                          <li key={r.id} className="py-1.5 flex flex-wrap justify-between gap-2">
                            <span className="font-medium text-slate-800">{r.title}</span>
                            <span className="text-slate-600">{formatCurrency(r.monthlyAmount)}/month · {r.cadence.toLowerCase()} · {r.userConfirmed ? "confirmed by you" : `detected (${r.confidence.toLowerCase()} confidence)`}</span>
                          </li>
                        ))}
                      </ul>
                    </section>
                  )}
                </>
              )}
            </Section>
          )}

          {tab === "categories" && (
            <Section state={categories} what="the category forecast">
              {(d) => (
                <>
                  <ForecastNotes reliability={d.reliability} method={d.method} algorithmVersion={d.algorithmVersion} history={d.history}
                    horizonMonths={d.horizonMonths} warnings={d.warnings} assumptions={d.assumptions} />
                  <CategoryForecastCard data={d} />
                </>
              )}
            </Section>
          )}

          {tab === "budgets" && (
            <Section state={budget} what="the budget outlook">{(d) => <BudgetOutlookCard data={d} />}</Section>
          )}

          {tab === "savings" && (
            <Section state={savings} what="the savings outlook">
              {(d) => (
                <>
                  <ForecastNotes reliability={d.reliability} method={d.method} algorithmVersion={d.algorithmVersion} history={d.history}
                    horizonMonths={d.horizonMonths} warnings={d.warnings} assumptions={d.assumptions} />
                  <SavingsOutlookCard data={d} />
                </>
              )}
            </Section>
          )}

          {tab === "scenarios" && (
            <Section state={cash} what="the baseline forecast">
              {(c) =>
                categories.data ? (
                  <ScenarioSimulatorCard cashFlow={c} categories={categories.data} horizonMonths={horizon} />
                ) : (
                  <Section state={categories} what="the category forecast">{() => null}</Section>
                )
              }
            </Section>
          )}

          {tab === "evaluation" && (
            <Section state={backtest} what="the evaluation">{(d) => <BacktestCard data={d} />}</Section>
          )}
        </div>
      </main>
    </div>
  );
}
