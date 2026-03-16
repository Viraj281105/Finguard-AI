import { useState } from "react";
import { loanAPI, type Loan, type LoanType } from "../services/api";

interface Props {
  onClose: () => void;
  onAdded: (loan: Loan) => void;
}

const TYPES: LoanType[] = ["HOME", "CAR", "PERSONAL", "EDUCATION", "OTHER"];

export default function AddLoanModal({ onClose, onAdded }: Props) {
  const [form, setForm] = useState({
    name: "",
    type: "PERSONAL" as LoanType,
    principal: "",
    emiAmount: "",
    interestRate: "",
    startDate: "",
    endDate: "",
    active: true,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const created = await loanAPI.create({
        name: form.name,
        type: form.type,
        principal: parseFloat(form.principal),
        emiAmount: parseFloat(form.emiAmount),
        interestRate: parseFloat(form.interestRate),
        startDate: form.startDate,
        endDate: form.endDate,
        active: form.active,
      });
      onAdded(created);
      onClose();
    } catch {
      setError("Failed to create loan. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4 py-8 overflow-y-auto"
         style={{ background: "rgba(0,0,0,0.7)", backdropFilter: "blur(4px)" }}>
      <div className="w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl p-6 shadow-2xl my-auto">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-semibold text-white">Add Loan</h2>
          <button onClick={onClose} className="text-white/30 hover:text-white transition-colors text-xl leading-none">✕</button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Loan Name</label>
            <input
              required
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. HDFC Personal Loan"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#fb7185]/40 transition-colors"
            />
          </div>

          {/* Type */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Type</label>
            <div className="flex gap-2 flex-wrap">
              {TYPES.map(t => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setForm({ ...form, type: t })}
                  className={`px-3 py-2 rounded-xl text-xs font-medium transition-all border ${
                    form.type === t
                      ? "bg-[#fb7185]/20 text-[#fb7185] border-[#fb7185]/30"
                      : "text-white/30 border-white/[0.08] hover:text-white/60"
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          {/* Principal + EMI row */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Principal (₹)</label>
              <input
                required
                type="number"
                min="0"
                step="0.01"
                value={form.principal}
                onChange={e => setForm({ ...form, principal: e.target.value })}
                placeholder="0"
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                           placeholder-white/20 focus:outline-none focus:border-[#fb7185]/40 transition-colors"
              />
            </div>
            <div>
              <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">EMI / month (₹)</label>
              <input
                required
                type="number"
                min="0"
                step="0.01"
                value={form.emiAmount}
                onChange={e => setForm({ ...form, emiAmount: e.target.value })}
                placeholder="0"
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                           placeholder-white/20 focus:outline-none focus:border-[#fb7185]/40 transition-colors"
              />
            </div>
          </div>

          {/* Interest rate */}
          <div>
            <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Interest Rate (%)</label>
            <input
              required
              type="number"
              min="0"
              step="0.01"
              value={form.interestRate}
              onChange={e => setForm({ ...form, interestRate: e.target.value })}
              placeholder="e.g. 12.5"
              className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                         placeholder-white/20 focus:outline-none focus:border-[#fb7185]/40 transition-colors"
            />
          </div>

          {/* Start + End date row */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Start Date</label>
              <input
                required
                type="date"
                value={form.startDate}
                onChange={e => setForm({ ...form, startDate: e.target.value })}
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                           focus:outline-none focus:border-[#fb7185]/40 transition-colors"
                style={{ colorScheme: "dark" }}
              />
            </div>
            <div>
              <label className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">End Date</label>
              <input
                required
                type="date"
                value={form.endDate}
                onChange={e => setForm({ ...form, endDate: e.target.value })}
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white
                           focus:outline-none focus:border-[#fb7185]/40 transition-colors"
                style={{ colorScheme: "dark" }}
              />
            </div>
          </div>

          {/* Active toggle */}
          <div className="flex items-center justify-between py-2">
            <span className="text-sm text-white/60">Loan is currently active</span>
            <button
              type="button"
              onClick={() => setForm({ ...form, active: !form.active })}
              className={`w-11 h-6 rounded-full transition-all relative ${
                form.active ? "bg-[#fb7185]" : "bg-white/10"
              }`}
            >
              <span className={`absolute top-1 w-4 h-4 rounded-full bg-white transition-all ${
                form.active ? "left-6" : "left-1"
              }`} />
            </button>
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
              className="flex-1 py-3 rounded-xl text-sm font-semibold bg-[#fb7185] text-black
                         hover:bg-[#f43f5e] disabled:opacity-50 transition-all"
            >
              {loading ? "Adding..." : "Add Loan"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
