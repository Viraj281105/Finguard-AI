import { useState } from "react";
import { transactionAPI, type Transaction, type TransactionType } from "../services/api";

interface Props {
  onClose: () => void;
  onAdded: (tx: Transaction) => void;
}

const CATEGORIES = [
  "Salary", "Freelance", "Business", "Investment Returns",
  "Food", "Transport", "Rent", "Utilities", "Shopping",
  "Entertainment", "Healthcare", "Education", "EMI", "Other"
];

export default function AddTransactionModal({ onClose, onAdded }: Props) {
  const [form, setForm] = useState({
    title: "",
    amount: "",
    type: "INCOME" as TransactionType,
    category: "Salary",
    note: "",
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const created = await transactionAPI.create({
        title: form.title,
        amount: parseFloat(form.amount),
        type: form.type,
        category: form.category,
        note: form.note,
      });
      onAdded(created);
      onClose();
    } catch {
      setError("Failed to create transaction. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4"
         style={{ background: "rgba(0,0,0,0.7)", backdropFilter: "blur(4px)" }}>
      <div className="w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl p-6 shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-semibold text-white">Add Transaction</h2>
          <button onClick={onClose} className="text-white/30 hover:text-white transition-colors text-xl leading-none">✕</button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Type toggle */}
          <div className="flex rounded-xl overflow-hidden border border-white/[0.08]">
            {(["INCOME", "EXPENSE"] as TransactionType[]).map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setForm({ ...form, type: t })}
                className={`flex-1 py-2.5 text-sm font-semibold transition-all ${
                  form.type === t
                    ? t === "INCOME"
                      ? "bg-emerald-500/20 text-emerald-400"
                      : "bg-rose-500/20 text-rose-400"
                    : "text-white/30 hover:text-white/60"
                }`}
              >
                {t}
              </button>
            ))}
          </div>

          {/* Title */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Title</label>
            <input
              required
              value={form.title}
              onChange={e => setForm({ ...form, title: e.target.value })}
              placeholder="e.g. March Salary"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#00d4ff]/40 transition-colors"
            />
          </div>

          {/* Amount */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Amount (₹)</label>
            <input
              required
              type="number"
              min="0"
              step="0.01"
              value={form.amount}
              onChange={e => setForm({ ...form, amount: e.target.value })}
              placeholder="0"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#00d4ff]/40 transition-colors"
            />
          </div>

          {/* Category */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Category</label>
            <select
              value={form.category}
              onChange={e => setForm({ ...form, category: e.target.value })}
              className="w-full bg-[#0f1117] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         focus:outline-none focus:border-[#00d4ff]/40 transition-colors"
            >
              {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>

          {/* Note */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Note (optional)</label>
            <input
              value={form.note}
              onChange={e => setForm({ ...form, note: e.target.value })}
              placeholder="Any extra details..."
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#00d4ff]/40 transition-colors"
            />
          </div>

          {error && <p className="text-rose-400 text-xs">{error}</p>}

          {/* Actions */}
          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-3 rounded-xl text-sm text-white/40 hover:text-white border border-white/[0.08]
                         hover:border-white/20 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex-1 py-3 rounded-xl text-sm font-semibold bg-[#00d4ff] text-black
                         hover:bg-[#00b8d9] disabled:opacity-50 transition-all"
            >
              {loading ? "Adding..." : "Add Transaction"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
