import { useNavigate } from "react-router-dom";
import { authAPI } from "../auth/api";
import { clearToken } from "../../shared/auth/tokenStorage";
import { useResource } from "../../shared/hooks/useResource";
import { formatCurrency } from "../../shared/utils/format";
import ErrorBanner from "../../shared/components/ErrorBanner";
import FullPageSpinner from "../../shared/components/Spinner";
import CsvUpload from "../statement-import/CsvUpload";
import { loadDashboardData } from "./api";
import RecentTransactionRow from "./RecentTransactionRow";
import SummaryCard from "./SummaryCard";

const LOAD_ERROR = "Failed to load dashboard data. Is the backend running?";

const NAV_LINKS = [
  { label: "Transactions", to: "/transactions" },
  { label: "Investments", to: "/investments" },
  { label: "Loans", to: "/loans" },
];

export default function DashboardPage() {
  const navigate = useNavigate();
  const { data, loading, error, reload } = useResource(loadDashboardData, LOAD_ERROR);

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } finally {
      clearToken();
      navigate("/login");
    }
  };

  if (loading) return <FullPageSpinner label="Loading" />;

  const summary = data?.summary;
  const gainPct = data?.gainPct ?? "0.00";
  const recentTx = data?.recentTransactions ?? [];

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      <nav className="sticky top-0 z-50 border-b border-white/[0.05] bg-[#080a0e]/80 backdrop-blur-xl px-6 py-4">
        <div className="max-w-6xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xl">🛡️</span>
            <span className="font-bold text-lg tracking-tight">
              Fin<span className="text-[#00d4ff]">Guard</span>
            </span>
          </div>
          <div className="flex items-center gap-2">
            {NAV_LINKS.map((link) => (
              <button
                key={link.to}
                onClick={() => navigate(link.to)}
                className="px-4 py-2 text-sm text-white/60 hover:text-white transition-colors"
              >
                {link.label}
              </button>
            ))}
            <button
              onClick={handleLogout}
              className="ml-4 px-4 py-2 text-sm bg-white/5 hover:bg-white/10 rounded-xl transition-colors"
            >
              Logout
            </button>
          </div>
        </div>
      </nav>

      <main className="max-w-6xl mx-auto px-6 py-10">
        <div className="mb-10">
          <h1 className="text-4xl font-bold tracking-tight">Financial Overview</h1>
          <p className="text-white/40 mt-1 text-sm">Your real-time financial snapshot</p>
        </div>

        <ErrorBanner message={error} className="mb-6" />

        <div className="mb-10">
          <CsvUpload onUploadComplete={reload} />
        </div>

        {summary && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-10">
            <SummaryCard
              label="Total Income"
              value={formatCurrency(summary.totalIncome)}
              sub="All-time earnings"
              accent="#00d4ff"
              icon="💰"
            />
            <SummaryCard
              label="Total Expenses"
              value={formatCurrency(summary.totalExpenses)}
              sub={`Net: ${formatCurrency(summary.totalIncome - summary.totalExpenses)}`}
              accent="#f97316"
              icon="📤"
            />
            <SummaryCard
              label="Investments"
              value={formatCurrency(summary.totalCurrentValue)}
              sub={`${Number(gainPct) >= 0 ? "+" : ""}${gainPct}% vs invested`}
              accent="#a78bfa"
              icon="📈"
            />
            <SummaryCard
              label="Amount Invested"
              value={formatCurrency(summary.totalInvested)}
              sub="Total principal deployed"
              accent="#34d399"
              icon="🏦"
            />
            <SummaryCard
              label="Active Loans"
              value={`${summary.activeLoans}`}
              sub={`${formatCurrency(summary.totalEMI)} / month EMI`}
              accent="#fb7185"
              icon="🏷️"
            />
            <SummaryCard
              label="Monthly EMI Burden"
              value={formatCurrency(summary.totalEMI)}
              sub="Total active loan EMIs"
              accent="#fbbf24"
              icon="📅"
            />
          </div>
        )}

        <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] p-6">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-lg font-semibold">Recent Transactions</h2>
            <button
              onClick={() => navigate("/transactions")}
              className="text-xs text-[#00d4ff] hover:underline"
            >
              View all →
            </button>
          </div>
          {recentTx.length === 0 ? (
            <p className="text-white/30 text-sm text-center py-8">
              No transactions yet. Upload a bank statement above to get started.
            </p>
          ) : (
            recentTx.map((tx) => <RecentTransactionRow key={tx.id} tx={tx} />)
          )}
        </div>
      </main>
    </div>
  );
}
