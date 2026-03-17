import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  transactionAPI,
  investmentAPI,
  loanAPI,
  computeSummary,
  type DashboardSummary,
  type Transaction,
} from "../services/api";
import CsvUpload from "../components/CsvUpload";

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmt = (n: number) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(n);

const pct = (current: number, invested: number) => {
  if (invested === 0) return "0.00";
  return (((current - invested) / invested) * 100).toFixed(2);
};

// ─── Sub-components ───────────────────────────────────────────────────────────

function SummaryCard({
  label,
  value,
  sub,
  accent,
  icon,
}: {
  label: string;
  value: string;
  sub?: string;
  accent: string;
  icon: string;
}) {
  return (
    <div
      className="relative overflow-hidden rounded-2xl bg-[#0f1117] border border-white/[0.06] p-6
                 hover:border-white/[0.14] transition-all duration-300 group"
    >
      {/* accent glow */}
      <div
        className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-500"
        style={{
          background: `radial-gradient(ellipse at top left, ${accent}18 0%, transparent 60%)`,
        }}
      />
      <div className="relative z-10">
        <div className="flex items-start justify-between mb-4">
          <span
            className="text-2xl p-2 rounded-xl"
            style={{ background: `${accent}18` }}
          >
            {icon}
          </span>
          <span
            className="text-xs font-semibold tracking-widest uppercase px-2 py-1 rounded-full"
            style={{ background: `${accent}18`, color: accent }}
          >
            {label}
          </span>
        </div>
        <p className="text-3xl font-bold text-white tracking-tight">{value}</p>
        {sub && (
          <p className="text-sm mt-1" style={{ color: `${accent}cc` }}>
            {sub}
          </p>
        )}
      </div>
    </div>
  );
}

function RecentTxRow({ tx }: { tx: Transaction }) {
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
          <p className="text-xs text-white/30">{tx.category}</p>
        </div>
      </div>
      <span
        className={`text-sm font-semibold ${isIncome ? "text-emerald-400" : "text-rose-400"}`}
      >
        {isIncome ? "+" : "-"}
        {fmt(tx.amount)}
      </span>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function DashboardPage() {
  const navigate = useNavigate();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [recentTx, setRecentTx] = useState<Transaction[]>([]);
  const [gainPct, setGainPct] = useState("0.00");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  /*
   * loadDashboardData()
   * -------------------
   * Extracted into a useCallback so it can be called:
   *   1. On initial page load (useEffect below)
   *   2. After a successful CSV upload (passed to CsvUpload as onUploadComplete)
   */
  const loadDashboardData = useCallback(() => {
    const token = localStorage.getItem("token");
    if (!token) {
      navigate("/login");
      return;
    }

    setLoading(true);
    Promise.all([
      transactionAPI.getAll(),
      investmentAPI.getAll(),
      loanAPI.getAll(),
    ])
      .then(([txs, invs, loans]) => {
        setSummary(computeSummary(txs, invs, loans));
        setRecentTx(
          [...txs]
            .sort(
              (a, b) =>
                new Date(b.createdAt).getTime() -
                new Date(a.createdAt).getTime()
            )
            .slice(0, 5)
        );
        const totalInvested = invs.reduce((s, i) => s + i.amountInvested, 0);
        const totalCurrent = invs.reduce((s, i) => s + i.currentValue, 0);
        setGainPct(pct(totalCurrent, totalInvested));
      })
      .catch(() =>
        setError("Failed to load dashboard data. Is the backend running?")
      )
      .finally(() => setLoading(false));
  }, [navigate]);

  useEffect(() => {
    loadDashboardData();
  }, [loadDashboardData]);

  const handleLogout = () => {
    localStorage.removeItem("token");
    navigate("/login");
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-[#080a0e] flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 rounded-full border-2 border-[#00d4ff]/30 border-t-[#00d4ff] animate-spin" />
          <p className="text-white/40 text-sm tracking-widest uppercase">
            Loading
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      {/* ── Top nav ── */}
      <nav className="sticky top-0 z-50 border-b border-white/[0.05] bg-[#080a0e]/80 backdrop-blur-xl px-6 py-4">
        <div className="max-w-6xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xl">🛡️</span>
            <span className="font-bold text-lg tracking-tight">
              Fin<span className="text-[#00d4ff]">Guard</span>
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => navigate("/transactions")}
              className="px-4 py-2 text-sm text-white/60 hover:text-white transition-colors"
            >
              Transactions
            </button>
            <button
              onClick={() => navigate("/investments")}
              className="px-4 py-2 text-sm text-white/60 hover:text-white transition-colors"
            >
              Investments
            </button>
            <button
              onClick={() => navigate("/loans")}
              className="px-4 py-2 text-sm text-white/60 hover:text-white transition-colors"
            >
              Loans
            </button>
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
        {/* ── Header ── */}
        <div className="mb-10">
          <h1 className="text-4xl font-bold tracking-tight">
            Financial Overview
          </h1>
          <p className="text-white/40 mt-1 text-sm">
            Your real-time financial snapshot
          </p>
        </div>

        {error && (
          <div className="mb-6 px-4 py-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400 text-sm">
            {error}
          </div>
        )}

        {/* ── CSV Upload Component ── */}
        <div className="mb-10">
          <CsvUpload onUploadComplete={loadDashboardData} />
        </div>

        {/* ── Summary Cards ── */}
        {summary && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 mb-10">
            <SummaryCard
              label="Total Income"
              value={fmt(summary.totalIncome)}
              sub="All-time earnings"
              accent="#00d4ff"
              icon="💰"
            />
            <SummaryCard
              label="Total Expenses"
              value={fmt(summary.totalExpenses)}
              sub={`Net: ${fmt(summary.totalIncome - summary.totalExpenses)}`}
              accent="#f97316"
              icon="📤"
            />
            <SummaryCard
              label="Investments"
              value={fmt(summary.totalCurrentValue)}
              sub={`${Number(gainPct) >= 0 ? "+" : ""}${gainPct}% vs invested`}
              accent="#a78bfa"
              icon="📈"
            />
            <SummaryCard
              label="Amount Invested"
              value={fmt(summary.totalInvested)}
              sub="Total principal deployed"
              accent="#34d399"
              icon="🏦"
            />
            <SummaryCard
              label="Active Loans"
              value={`${summary.activeLoans}`}
              sub={`${fmt(summary.totalEMI)} / month EMI`}
              accent="#fb7185"
              icon="🏷️"
            />
            <SummaryCard
              label="Monthly EMI Burden"
              value={fmt(summary.totalEMI)}
              sub="Total active loan EMIs"
              accent="#fbbf24"
              icon="📅"
            />
          </div>
        )}

        {/* ── Recent Transactions ── */}
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
            recentTx.map((tx) => <RecentTxRow key={tx.id} tx={tx} />)
          )}
        </div>
      </main>
    </div>
  );
}