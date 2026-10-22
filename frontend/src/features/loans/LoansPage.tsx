import { useState } from "react";
import { useResource } from "../../shared/hooks/useResource";
import { formatCurrency, formatDate } from "../../shared/utils/format";
import ErrorBanner from "../../shared/components/ErrorBanner";
import PageNav, { AddButton } from "../../shared/components/PageNav";
import FullPageSpinner from "../../shared/components/Spinner";
import AddLoanModal from "./AddLoanModal";
import { loanAPI } from "./api";
import type { Loan, LoanType } from "./types";

const TYPE_COLORS: Record<LoanType, { bg: string; text: string }> = {
  HOME:      { bg: "bg-blue-500/10",   text: "text-blue-400" },
  CAR:       { bg: "bg-cyan-500/10",   text: "text-cyan-400" },
  PERSONAL:  { bg: "bg-rose-500/10",   text: "text-rose-400" },
  EDUCATION: { bg: "bg-green-500/10",  text: "text-green-400" },
  OTHER:     { bg: "bg-white/10",      text: "text-white/50" },
};

type StatusFilter = "ALL" | "ACTIVE" | "CLOSED";

const LOAD_ERROR = "Could not fetch loans.";

export default function LoansPage() {
  const { data, loading, error, update } = useResource(loanAPI.getAll, LOAD_ERROR);
  const [actionError, setActionError] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [showActive, setShowActive] = useState<StatusFilter>("ALL");
  const [showModal, setShowModal] = useState(false);

  const loans = data ?? [];

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this loan?")) return;
    setActionError("");
    setDeletingId(id);
    try {
      await loanAPI.delete(id);
      update((prev) => prev.filter((l) => l.id !== id));
    } catch {
      setActionError("Failed to delete.");
    } finally {
      setDeletingId(null);
    }
  };

  const handleAdded = (loan: Loan) => update((prev) => [loan, ...prev]);

  const filtered =
    showActive === "ALL"
      ? loans
      : showActive === "ACTIVE"
        ? loans.filter((l) => l.active)
        : loans.filter((l) => !l.active);

  const totalEMI = loans.filter((l) => l.active).reduce((s, l) => s + l.emiAmount, 0);
  const totalPrincipal = loans.filter((l) => l.active).reduce((s, l) => s + l.principal, 0);

  if (loading) return <FullPageSpinner accent="rose" />;

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      {showModal && <AddLoanModal onClose={() => setShowModal(false)} onAdded={handleAdded} />}

      <PageNav accent="rose" onAdd={() => setShowModal(true)} />

      <main className="max-w-5xl mx-auto px-6 py-10">
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Loans</h1>
            <p className="text-white/40 text-sm mt-1">{loans.length} total loans</p>
          </div>
          <div className="flex gap-3">
            <div className="px-4 py-2 rounded-xl bg-rose-500/10 border border-rose-500/20">
              <p className="text-xs text-rose-400/60 uppercase tracking-widest">Monthly EMI</p>
              <p className="text-sm font-semibold text-rose-400">{formatCurrency(totalEMI)}</p>
            </div>
            <div className="px-4 py-2 rounded-xl bg-orange-500/10 border border-orange-500/20">
              <p className="text-xs text-orange-400/60 uppercase tracking-widest">Total Principal</p>
              <p className="text-sm font-semibold text-orange-400">{formatCurrency(totalPrincipal)}</p>
            </div>
          </div>
        </div>

        <div className="flex gap-2 mb-6">
          {(["ALL", "ACTIVE", "CLOSED"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setShowActive(f)}
              className={`px-4 py-2 rounded-xl text-sm font-medium transition-all ${
                showActive === f ? "bg-white/10 text-white" : "text-white/40 hover:text-white/70"
              }`}
            >
              {f.charAt(0) + f.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        <ErrorBanner message={error || actionError} />

        <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] overflow-hidden">
          {filtered.length === 0 ? (
            <div className="text-center py-16 text-white/30">
              <p className="text-4xl mb-3">🏷️</p>
              <p className="text-sm mb-4">No loans found.</p>
              <AddButton accent="rose" onClick={() => setShowModal(true)}>
                + Add your first loan
              </AddButton>
            </div>
          ) : (
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/[0.06]">
                  {["Name", "Type", "EMI / mo", "Interest", "End Date", "Status", ""].map((h) => (
                    <th key={h} className={`px-5 py-4 text-xs text-white/30 uppercase tracking-widest font-medium ${h === "Name" ? "text-left" : "text-right"}`}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((loan) => {
                  const colors = TYPE_COLORS[loan.type];
                  return (
                    <tr key={loan.id} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors">
                      <td className="px-5 py-4">
                        <p className="text-sm font-medium text-white/90">{loan.name}</p>
                        <p className="text-xs text-white/30 mt-0.5">Principal: {formatCurrency(loan.principal)}</p>
                      </td>
                      <td className="px-5 py-4 text-right">
                        <span className={`text-xs font-medium px-2 py-1 rounded-lg ${colors.bg} ${colors.text}`}>{loan.type}</span>
                      </td>
                      <td className="px-5 py-4 text-right text-sm font-medium text-white">{formatCurrency(loan.emiAmount)}</td>
                      <td className="px-5 py-4 text-right text-sm text-white/60">{loan.interestRate}%</td>
                      <td className="px-5 py-4 text-right text-xs text-white/40">{formatDate(loan.endDate)}</td>
                      <td className="px-5 py-4 text-right">
                        <span className={`text-xs font-semibold px-2 py-1 rounded-lg ${
                          loan.active ? "bg-emerald-500/10 text-emerald-400" : "bg-white/5 text-white/30"
                        }`}>
                          {loan.active ? "Active" : "Closed"}
                        </span>
                      </td>
                      <td className="px-5 py-4 text-right">
                        <button
                          onClick={() => handleDelete(loan.id)}
                          disabled={deletingId === loan.id}
                          aria-label={`Delete ${loan.name}`}
                          className="text-white/20 hover:text-rose-400 transition-colors text-sm disabled:opacity-40"
                        >
                          {deletingId === loan.id ? "…" : "✕"}
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
