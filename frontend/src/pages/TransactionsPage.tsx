import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { transactionAPI, type Transaction } from "../services/api";
import AddTransactionModal from "../components/AddTransactionModal";

const fmt = (n: number) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(n);

const formatDate = (iso: string) =>
  new Date(iso).toLocaleDateString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

export default function TransactionsPage() {
  const navigate = useNavigate();
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [filter, setFilter] = useState<"ALL" | "INCOME" | "EXPENSE">("ALL");
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) { navigate("/login"); return; }

    transactionAPI
      .getAll()
      .then((data) =>
        setTransactions(
          [...data].sort(
            (a, b) =>
              new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
          )
        )
      )
      .catch(() => setError("Could not fetch transactions."))
      .finally(() => setLoading(false));
  }, [navigate]);

  const handleDelete = async (id: number) => {
    if (!window.confirm("Delete this transaction?")) return;
    setDeletingId(id);
    try {
      await transactionAPI.delete(id);
      setTransactions((prev) => prev.filter((t) => t.id !== id));
    } catch {
      alert("Failed to delete. Please try again.");
    } finally {
      setDeletingId(null);
    }
  };

  const handleAdded = (tx: Transaction) => {
    setTransactions((prev) => [tx, ...prev]);
  };

  const filtered =
    filter === "ALL" ? transactions : transactions.filter((t) => t.type === filter);

  const totalIncome = transactions
    .filter((t) => t.type === "INCOME")
    .reduce((s, t) => s + t.amount, 0);
  const totalExpense = transactions
    .filter((t) => t.type === "EXPENSE")
    .reduce((s, t) => s + t.amount, 0);

  if (loading) {
    return (
      <div className="min-h-screen bg-[#080a0e] flex items-center justify-center">
        <div className="w-10 h-10 rounded-full border-2 border-[#00d4ff]/30 border-t-[#00d4ff] animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      {showModal && (
        <AddTransactionModal
          onClose={() => setShowModal(false)}
          onAdded={handleAdded}
        />
      )}

      <nav className="sticky top-0 z-40 border-b border-white/[0.05] bg-[#080a0e]/80 backdrop-blur-xl px-6 py-4">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <button
            onClick={() => navigate("/dashboard")}
            className="text-white/50 hover:text-white transition-colors text-sm"
          >
            ← Dashboard
          </button>
          <span className="font-bold text-lg tracking-tight">
            Fin<span className="text-[#00d4ff]">Guard</span>
          </span>
          <button
            onClick={() => setShowModal(true)}
            className="px-4 py-2 text-sm bg-[#00d4ff] text-black font-semibold rounded-xl
                       hover:bg-[#00b8d9] transition-colors"
          >
            + Add
          </button>
        </div>
      </nav>

      <main className="max-w-5xl mx-auto px-6 py-10">
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Transactions</h1>
            <p className="text-white/40 text-sm mt-1">{transactions.length} total entries</p>
          </div>
          <div className="flex gap-3">
            <div className="px-4 py-2 rounded-xl bg-emerald-500/10 border border-emerald-500/20">
              <p className="text-xs text-emerald-400/60 uppercase tracking-widest">Income</p>
              <p className="text-sm font-semibold text-emerald-400">{fmt(totalIncome)}</p>
            </div>
            <div className="px-4 py-2 rounded-xl bg-rose-500/10 border border-rose-500/20">
              <p className="text-xs text-rose-400/60 uppercase tracking-widest">Expenses</p>
              <p className="text-sm font-semibold text-rose-400">{fmt(totalExpense)}</p>
            </div>
          </div>
        </div>

        <div className="flex gap-2 mb-6">
          {(["ALL", "INCOME", "EXPENSE"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-4 py-2 rounded-xl text-sm font-medium transition-all ${
                filter === f ? "bg-white/10 text-white" : "text-white/40 hover:text-white/70"
              }`}
            >
              {f.charAt(0) + f.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400 text-sm">
            {error}
          </div>
        )}

        <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] overflow-hidden">
          {filtered.length === 0 ? (
            <div className="text-center py-16 text-white/30">
              <p className="text-4xl mb-3">📭</p>
              <p className="text-sm mb-4">No transactions found.</p>
              <button
                onClick={() => setShowModal(true)}
                className="px-4 py-2 text-sm bg-[#00d4ff] text-black font-semibold rounded-xl hover:bg-[#00b8d9] transition-colors"
              >
                + Add your first transaction
              </button>
            </div>
          ) : (
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/[0.06]">
                  <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Title</th>
                  <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Category</th>
                  <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Type</th>
                  <th className="text-right px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Amount</th>
                  <th className="text-right px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Date</th>
                  <th className="px-6 py-4" />
                </tr>
              </thead>
              <tbody>
                {filtered.map((tx) => (
                  <tr key={tx.id} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors">
                    <td className="px-6 py-4">
                      <p className="text-sm font-medium text-white/90">{tx.title}</p>
                      {tx.note && <p className="text-xs text-white/30 mt-0.5">{tx.note}</p>}
                    </td>
                    <td className="px-6 py-4">
                      <span className="text-xs px-2 py-1 rounded-lg bg-white/5 text-white/50">{tx.category}</span>
                    </td>
                    <td className="px-6 py-4">
                      <span className={`text-xs font-semibold px-2 py-1 rounded-lg ${
                        tx.type === "INCOME" ? "bg-emerald-500/10 text-emerald-400" : "bg-rose-500/10 text-rose-400"
                      }`}>
                        {tx.type}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <span className={`text-sm font-semibold ${tx.type === "INCOME" ? "text-emerald-400" : "text-rose-400"}`}>
                        {tx.type === "INCOME" ? "+" : "-"}{fmt(tx.amount)}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right text-xs text-white/30">{formatDate(tx.createdAt)}</td>
                    <td className="px-6 py-4 text-right">
                      <button
                        onClick={() => handleDelete(tx.id)}
                        disabled={deletingId === tx.id}
                        className="text-white/20 hover:text-rose-400 transition-colors text-sm disabled:opacity-40"
                      >
                        {deletingId === tx.id ? "…" : "✕"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </main>
    </div>
  );
}
