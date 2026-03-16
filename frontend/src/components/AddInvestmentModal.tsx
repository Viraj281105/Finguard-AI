import { useState } from "react";
import { investmentAPI, type Investment, type InvestmentType } from "../services/api";

interface Props {
  onClose: () => void;
  onAdded: (inv: Investment) => void;
}

const TYPES: InvestmentType[] = ["STOCKS", "MUTUAL_FUND", "FD", "CRYPTO", "GOLD", "OTHER"];

export default function AddInvestmentModal({ onClose, onAdded }: Props) {
  const [form, setForm] = useState({
    name: "",
    type: "MUTUAL_FUND" as InvestmentType,
    amountInvested: "",
    currentValue: "",
    notes: "",
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const created = await investmentAPI.create({
        name: form.name,
        type: form.type,
        amountInvested: parseFloat(form.amountInvested),
        currentValue: parseFloat(form.currentValue || form.amountInvested),
        notes: form.notes,
      });
      onAdded(created);
      onClose();
    } catch {
      setError("Failed to create investment. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4"
         style={{ background: "rgba(0,0,0,0.7)", backdropFilter: "blur(4px)" }}>
      <div className="w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl p-6 shadow-2xl">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-semibold text-white">Add Investment</h2>
          <button onClick={onClose} className="text-white/30 hover:text-white transition-colors text-xl leading-none">✕</button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Name</label>
            <input
              required
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. Nifty 50 Index Fund"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#a78bfa]/40 transition-colors"
            />
          </div>

          {/* Type */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Type</label>
            <div className="grid grid-cols-3 gap-2">
              {TYPES.map(t => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setForm({ ...form, type: t })}
                  className={`py-2 rounded-xl text-xs font-medium transition-all border ${
                    form.type === t
                      ? "bg-[#a78bfa]/20 text-[#a78bfa] border-[#a78bfa]/30"
                      : "text-white/30 border-white/[0.08] hover:text-white/60"
                  }`}
                >
                  {t.replace("_", " ")}
                </button>
              ))}
            </div>
          </div>

          {/* Amount Invested */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Amount Invested (₹)</label>
            <input
              required
              type="number"
              min="0"
              step="0.01"
              value={form.amountInvested}
              onChange={e => setForm({ ...form, amountInvested: e.target.value })}
              placeholder="0"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#a78bfa]/40 transition-colors"
            />
          </div>

          {/* Current Value */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">
              Current Value (₹) <span className="text-white/20 normal-case">— leave blank to match invested</span>
            </label>
            <input
              type="number"
              min="0"
              step="0.01"
              value={form.currentValue}
              onChange={e => setForm({ ...form, currentValue: e.target.value })}
              placeholder={form.amountInvested || "0"}
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#a78bfa]/40 transition-colors"
            />
          </div>

          {/* Notes */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Notes (optional)</label>
            <input
              value={form.notes}
              onChange={e => setForm({ ...form, notes: e.target.value })}
              placeholder="Any extra details..."
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#a78bfa]/40 transition-colors"
            />
          </div>

          {error && <p className="text-rose-400 text-xs">{error}</p>}

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
              className="flex-1 py-3 rounded-xl text-sm font-semibold bg-[#a78bfa] text-black
                         hover:bg-[#9061f9] disabled:opacity-50 transition-all"
            >
              {loading ? "Adding..." : "Add Investment"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
