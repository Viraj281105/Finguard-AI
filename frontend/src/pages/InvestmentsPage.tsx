import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { investmentAPI, type Investment, type InvestmentType } from "../services/api";
import AddInvestmentModal from "../components/AddInvestmentModal";

const fmt = (n: number) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(n);

const TYPE_COLORS: Record<InvestmentType, { bg: string; text: string }> = {
  STOCKS:      { bg: "bg-blue-500/10",   text: "text-blue-400" },
  MUTUAL_FUND: { bg: "bg-purple-500/10", text: "text-purple-400" },
  FD:          { bg: "bg-yellow-500/10", text: "text-yellow-400" },
  CRYPTO:      { bg: "bg-orange-500/10", text: "text-orange-400" },
  GOLD:        { bg: "bg-amber-500/10",  text: "text-amber-400" },
  OTHER:       { bg: "bg-white/10",      text: "text-white/50" },
};

export default function InvestmentsPage() {
  const navigate = useNavigate();
  const [investments, setInvestments] = useState<Investment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) { navigate("/login"); return; }

    investmentAPI
      .getAll()
      .then((data) =>
        setInvestments(
          [...data].sort(
            (a, b) =>
              new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
          )
        )
      )
      .catch(() => setError("Could not fetch investments."))
      .finally(() => setLoading(false));
  }, [navigate]);

  const handleDelete = async (id: number) => {
    if (!window.confirm("Delete this investment?")) return;
    setDeletingId(id);
    try {
      await investmentAPI.delete(id);
      setInvestments((prev) => prev.filter((i) => i.id !== id));
    } catch {
      alert("Failed to delete.");
    } finally {
      setDeletingId(null);
    }
  };

  const handleAdded = (inv: Investment) => {
    setInvestments((prev) => [inv, ...prev]);
  };

  const totalInvested = investments.reduce((s, i) => s + i.amountInvested, 0);
  const totalCurrent  = investments.reduce((s, i) => s + i.currentValue, 0);
  const overallGain   = totalCurrent - totalInvested;
  const overallPct    = totalInvested > 0
    ? ((overallGain / totalInvested) * 100).toFixed(2)
    : "0.00";

  if (loading) {
    return (
      <div className="min-h-screen bg-[#080a0e] flex items-center justify-center">
        <div className="w-10 h-10 rounded-full border-2 border-[#a78bfa]/30 border-t-[#a78bfa] animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      {showModal && (
        <AddInvestmentModal
          onClose={() => setShowModal(false)}
          onAdded={handleAdded}
        />
      )}

      <nav className="sticky top-0 z-40 border-b border-white/[0.05] bg-[#080a0e]/80 backdrop-blur-xl px-6 py-4">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate("/dashboard")} className="text-white/50 hover:text-white transition-colors text-sm">
            ← Dashboard
          </button>
          <span className="font-bold text-lg tracking-tight">Fin<span className="text-[#00d4ff]">Guard</span></span>
          <button
            onClick={() => setShowModal(true)}
            className="px-4 py-2 text-sm bg-[#a78bfa] text-black font-semibold rounded-xl hover:bg-[#9061f9] transition-colors"
          >
            + Add
          </button>
        </div>
      </nav>

      <main className="max-w-5xl mx-auto px-6 py-10">
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Investments</h1>
            <p className="text-white/40 text-sm mt-1">{investments.length} holdings</p>
          </div>
          <div className="flex gap-3">
            <div className="px-4 py-2 rounded-xl bg-purple-500/10 border border-purple-500/20">
              <p className="text-xs text-purple-400/60 uppercase tracking-widest">Invested</p>
              <p className="text-sm font-semibold text-purple-400">{fmt(totalInvested)}</p>
            </div>
            <div className={`px-4 py-2 rounded-xl border ${
              overallGain >= 0 ? "bg-emerald-500/10 border-emerald-500/20" : "bg-rose-500/10 border-rose-500/20"
            }`}>
              <p className={`text-xs uppercase tracking-widest ${overallGain >= 0 ? "text-emerald-400/60" : "text-rose-400/60"}`}>P&L</p>
              <p className={`text-sm font-semibold ${overallGain >= 0 ? "text-emerald-400" : "text-rose-400"}`}>
                {overallGain >= 0 ? "+" : ""}{overallPct}%
              </p>
            </div>
          </div>
        </div>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400 text-sm">{error}</div>
        )}

        <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] overflow-hidden">
          {investments.length === 0 ? (
            <div className="text-center py-16 text-white/30">
              <p className="text-4xl mb-3">📊</p>
              <p className="text-sm mb-4">No investments yet.</p>
              <button
                onClick={() => setShowModal(true)}
                className="px-4 py-2 text-sm bg-[#a78bfa] text-black font-semibold rounded-xl hover:bg-[#9061f9] transition-colors"
              >
                + Add your first investment
              </button>
            </div>
          ) : (
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/[0.06]">
                  {["Name", "Type", "Invested", "Current Value", "P&L", ""].map((h) => (
                    <th key={h} className={`px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium ${h === "Name" ? "text-left" : "text-right"}`}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {investments.map((inv) => {
                  const gain = inv.currentValue - inv.amountInvested;
                  const gainP = inv.amountInvested > 0 ? ((gain / inv.amountInvested) * 100).toFixed(2) : "0.00";
                  const colors = TYPE_COLORS[inv.type];
                  return (
                    <tr key={inv.id} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors">
                      <td className="px-6 py-4">
                        <p className="text-sm font-medium text-white/90">{inv.name}</p>
                        {inv.notes && <p className="text-xs text-white/30 mt-0.5">{inv.notes}</p>}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <span className={`text-xs font-medium px-2 py-1 rounded-lg ${colors.bg} ${colors.text}`}>
                          {inv.type.replace("_", " ")}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-right text-sm text-white/70">{fmt(inv.amountInvested)}</td>
                      <td className="px-6 py-4 text-right text-sm font-medium text-white">{fmt(inv.currentValue)}</td>
                      <td className="px-6 py-4 text-right">
                        <span className={`text-sm font-semibold ${gain >= 0 ? "text-emerald-400" : "text-rose-400"}`}>
                          {gain >= 0 ? "+" : ""}{gainP}%
                        </span>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <button
                          onClick={() => handleDelete(inv.id)}
                          disabled={deletingId === inv.id}
                          className="text-white/20 hover:text-rose-400 transition-colors text-sm disabled:opacity-40"
                        >
                          {deletingId === inv.id ? "…" : "✕"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </main>
    </div>
  );
}
