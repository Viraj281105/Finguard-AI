import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { clearToken } from "../../shared/auth/tokenStorage";
import ErrorBanner from "../../shared/components/ErrorBanner";
import FullPageSpinner from "../../shared/components/Spinner";
import { useResource } from "../../shared/hooks/useResource";
import { formatCurrency } from "../../shared/utils/format";
import { authAPI } from "../auth/api";
import CsvUpload from "../statement-import/CsvUpload";
import CashFlowChart from "../analytics/components/CashFlowChart";
import CategoryBreakdownChart from "../analytics/components/CategoryBreakdownChart";
import DataQualityCard from "../analytics/components/DataQualityCard";
import DateRangeSelector from "../analytics/components/DateRangeSelector";
import InsightsFeed from "../analytics/components/InsightsFeed";
import KpiCard from "../analytics/components/KpiCard";
import RecurringExpensesCard from "../analytics/components/RecurringExpensesCard";
import UnusualSpendingCard from "../analytics/components/UnusualSpendingCard";
import BudgetHealthPreviewCard from "../budgeting/components/BudgetHealthPreviewCard";
import ForecastPreviewCard from "../forecasting/components/ForecastPreviewCard";
import RecommendationsPanel from "../recommendations/RecommendationsPanel";
import { loadDashboardData } from "./api";
import RecentTransactionRow from "./RecentTransactionRow";

const LOAD_ERROR = "Failed to load financial analytics. Is the backend running?";

const NAV_LINKS = [
  { label: "Transactions", to: "/transactions" },
  { label: "Budgets & Goals", to: "/budgets" },
  { label: "Forecasting", to: "/forecasting" },
  { label: "Investments", to: "/investments" },
  { label: "Loans", to: "/loans" },
];

export default function DashboardPage() {
  const navigate = useNavigate();
  const [dateRange, setDateRange] = useState<{ from?: string; to?: string }>({});

  const fetchDashboard = useCallback(
    () => loadDashboardData(dateRange.from, dateRange.to),
    [dateRange.from, dateRange.to]
  );

  const { data, loading, error, reload } = useResource(fetchDashboard, LOAD_ERROR);
  // The recommendations panel and the forecast preview load their own data. After an import they must be re-created or
  // they keep showing the pre-import answer ("not enough history") until a manual reload (found by the end-to-end audit).
  const [importCount, setImportCount] = useState(0);
  const handleImported = () => {
    reload();
    setImportCount((n) => n + 1);
  };

  const handleRangeChange = (from?: string, to?: string) => {
    setDateRange({ from, to });
  };

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } finally {
      clearToken();
      navigate("/login");
    }
  };

  if (loading) return <FullPageSpinner label="Loading Financial Analytics..." />;

  const summary = data?.summary;
  const monthly = data?.monthly ?? [];
  const categories = data?.categories ?? null;
  const recurring = data?.recurring ?? null;
  const quality = data?.dataQuality ?? null;
  const insights = data?.insights?.insights ?? summary?.topInsights ?? [];
  const outliers = data?.insights?.unusualTransactions ?? [];
  const recentTx = data?.recentTransactions ?? [];

  const isNetPositive = summary && summary.netCashFlow >= 0;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 font-sans antialiased">
      {/* Header Navigation */}
      <nav className="sticky top-0 z-50 border-b border-slate-200/80 bg-white/90 backdrop-blur-md px-6 py-3.5">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <span className="text-xl">🛡️</span>
            <span className="font-bold text-lg tracking-tight text-slate-900">
              Fin<span className="text-sky-600">Guard</span>
              <span className="text-[11px] font-semibold text-slate-500 ml-1.5 px-2 py-0.5 rounded-full bg-slate-100 border border-slate-200">
                AI Intelligence
              </span>
            </span>
          </div>

          <div className="flex items-center gap-1 sm:gap-2">
            {NAV_LINKS.map((link) => (
              <button
                key={link.to}
                onClick={() => navigate(link.to)}
                className="px-3.5 py-1.5 text-xs font-semibold text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded-xl transition-colors"
              >
                {link.label}
              </button>
            ))}
            <button
              onClick={handleLogout}
              className="ml-2 sm:ml-4 px-3.5 py-1.5 text-xs font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors border border-slate-200/60"
            >
              Sign out
            </button>
          </div>
        </div>
      </nav>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-8">
        {/* Page Title & Hero */}
        <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-slate-900">
              Financial Intelligence & Analytics
            </h1>
            <p className="text-slate-500 text-xs sm:text-sm mt-1">
              Grounded, server-verified personal finance metrics, cash flow trends, and explainable observations
            </p>
          </div>

          {summary && summary.period && (
            <div className="text-xs font-medium text-slate-500 bg-white px-3 py-1.5 rounded-xl border border-slate-200 shadow-2xs">
              Period:{" "}
              <strong className="text-slate-800">
                {summary.period.from && summary.period.to
                  ? `${summary.period.from} to ${summary.period.to}`
                  : "All available history"}
              </strong>
            </div>
          )}
        </div>

        <ErrorBanner message={error} className="mb-6" />

        {/* Date Range Selector */}
        <DateRangeSelector onRangeChange={handleRangeChange} />

        {/* Statement Upload Box */}
        <div className="mb-8 bg-white p-5 rounded-2xl border border-slate-200/80 shadow-xs">
          <div className="mb-3">
            <h2 className="text-sm font-bold text-slate-900">Import Bank Statement</h2>
            <p className="text-xs text-slate-500">
              Upload CSV statements (HDFC, ICICI, SBI, Axis, Kotak) to refresh your financial intelligence
            </p>
          </div>
          <CsvUpload onUploadComplete={handleImported} />
        </div>

        {/* Empty state for new users */}
        {quality?.totalTransactions === 0 && recentTx.length === 0 && (
          <div className="mb-8 p-8 rounded-2xl bg-white border border-slate-200/80 shadow-sm text-center">
            <div className="w-12 h-12 rounded-2xl bg-sky-50 text-sky-600 flex items-center justify-center mx-auto mb-3 text-2xl">
              📊
            </div>
            <h2 className="text-lg font-bold text-slate-900 mb-1">No Financial Data Available</h2>
            <p className="text-sm text-slate-500 max-w-md mx-auto mb-4">
              We need transaction history to calculate cash flow, savings rate, and spending intelligence. Upload a bank statement above or record a transaction to begin.
            </p>
            <button
              onClick={() => navigate("/transactions")}
              className="px-4 py-2 rounded-xl text-xs font-semibold bg-sky-600 hover:bg-sky-700 text-white transition-colors"
            >
              Add Transaction
            </button>
          </div>
        )}

        {summary && (
          <>
            {/* KPI Metrics Row */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
              <KpiCard
                label="Total Income"
                value={formatCurrency(summary.totalIncome)}
                sub="Earnings & inflows"
                changePct={summary.momComparison?.incomeChangePct}
                changeLabel="vs prior period"
              />

              <KpiCard
                label="Net Expenses"
                value={formatCurrency(summary.totalExpenses)}
                sub={
                  summary.totalRefunds > 0
                    ? `Gross: ${formatCurrency(summary.grossExpenses)} (Refunds: -${formatCurrency(summary.totalRefunds)})`
                    : "Consumption & debt service"
                }
                changePct={summary.momComparison?.expenseChangePct}
                changeLabel="vs prior period"
                isInverseTrend={true}
              />

              <KpiCard
                label="Net Cash Flow"
                value={formatCurrency(summary.netCashFlow)}
                sub={isNetPositive ? "Cash surplus saved" : "Cash deficit (expenses > income)"}
                badge={isNetPositive ? "Surplus" : "Deficit"}
                changePct={summary.momComparison?.netChange ? Number(summary.momComparison.netChange) : undefined}
                changeLabel="net change"
              />

              <KpiCard
                label="Savings Rate"
                value={summary.savingsRate !== null ? `${summary.savingsRate}%` : "N/A"}
                sub={
                  summary.savingsRateApplicable
                    ? "Share of income retained"
                    : "Requires recorded income"
                }
                caveat={summary.savingsRateCaveat}
              />
            </div>

            {/* Portfolio & Debt Row */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
              <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-xs">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Investment Holdings
                  </span>
                  <span
                    className={`text-xs font-bold px-2 py-0.5 rounded-full ${
                      summary.unrealizedGain >= 0
                        ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                        : "bg-rose-50 text-rose-700 border border-rose-200"
                    }`}
                  >
                    {summary.unrealizedGain >= 0 ? "+" : ""}
                    {summary.gainPercentage}%
                  </span>
                </div>
                <div className="text-2xl font-bold text-slate-900 mb-1">
                  {formatCurrency(summary.totalCurrentValue)}
                </div>
                <div className="text-xs text-slate-500">
                  Principal invested: {formatCurrency(summary.totalInvested)} · Unrealized:{" "}
                  <strong className={summary.unrealizedGain >= 0 ? "text-emerald-700" : "text-rose-700"}>
                    {summary.unrealizedGain >= 0 ? "+" : ""}
                    {formatCurrency(summary.unrealizedGain)}
                  </strong>
                </div>
              </div>

              <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-xs">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Debt Obligations
                  </span>
                  <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-slate-100 text-slate-700 border border-slate-200">
                    {summary.activeLoansCount} Active Loan{summary.activeLoansCount !== 1 ? "s" : ""}
                  </span>
                </div>
                <div className="text-2xl font-bold text-slate-900 mb-1">
                  {formatCurrency(summary.totalMonthlyEmi)}
                  <span className="text-xs font-normal text-slate-500">/mo EMI</span>
                </div>
                <div className="text-xs text-slate-500">
                  Total outstanding principal commitments: {formatCurrency(summary.totalPrincipal)}
                </div>
              </div>
            </div>

            {/* Budgeting & Financial Goals Integration */}
            <BudgetHealthPreviewCard overview={data.budgetOverview} goals={data.goals} />

            {/* Predictive Forecasting Integration */}
            <ForecastPreviewCard key={`forecast-${importCount}`} />

            {/* Data-grounded general guidance */}
            <RecommendationsPanel key={`reco-${importCount}`} />

            {/* Visualizations Grid */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 mb-8">
              <div className="lg:col-span-7">
                <CashFlowChart data={monthly} />
              </div>
              <div className="lg:col-span-5">
                <CategoryBreakdownChart data={categories} />
              </div>
            </div>

            {/* Intelligence Observations & Attention Needed */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 mb-8">
              <div className="lg:col-span-7">
                <InsightsFeed insights={insights} />
              </div>

              <div className="lg:col-span-5 space-y-6">
                <RecurringExpensesCard data={recurring} />
                <UnusualSpendingCard outliers={outliers} />
              </div>
            </div>

            {/* Data Quality & Diagnostics Block */}
            <div className="mb-8">
              <DataQualityCard data={quality} />
            </div>
          </>
        )}

        {/* Recent Ledger Entries */}
        <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-base font-bold text-slate-900">Recent Transactions</h2>
              <p className="text-xs text-slate-500">Most recent ledger rows from your statements</p>
            </div>
            <button
              onClick={() => navigate("/transactions")}
              className="text-xs font-semibold text-sky-600 hover:text-sky-700 hover:underline"
            >
              View ledger ({recentTx.length} items) →
            </button>
          </div>

          {recentTx.length === 0 ? (
            <p className="text-slate-400 text-xs text-center py-8">
              No transactions recorded yet. Upload a bank statement above to get started.
            </p>
          ) : (
            <div className="divide-y divide-slate-100">
              {recentTx.map((tx) => (
                <RecentTransactionRow key={tx.id} tx={tx} />
              ))}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
