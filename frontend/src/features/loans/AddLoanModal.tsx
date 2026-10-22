import { useState } from "react";
import { apiErrorMessage } from "../../shared/api/client";
import { ACCENT_STYLES, inputClass } from "../../shared/components/accent";
import ErrorBanner from "../../shared/components/ErrorBanner";
import { Field, FormActions, ModalShell } from "../../shared/components/Modal";
import { loanAPI } from "./api";
import type { Loan, LoanType } from "./types";

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
    } catch (err) {
      setError(apiErrorMessage(err, "Failed to create loan. Please try again."));
    } finally {
      setLoading(false);
    }
  };

  return (
    <ModalShell title="Add Loan" onClose={onClose} scrollable>
      <form onSubmit={handleSubmit} className="space-y-4">
        <Field label="Loan Name">
          {(id) => (
            <input
              id={id}
              required
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. HDFC Personal Loan"
              className={inputClass("rose")}
            />
          )}
        </Field>

        <div>
          <p className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Type</p>
          <div className="flex gap-2 flex-wrap">
            {TYPES.map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setForm({ ...form, type: t })}
                className={`px-3 py-2 rounded-xl text-xs font-medium transition-all border ${
                  form.type === t
                    ? ACCENT_STYLES.rose.selected
                    : "text-white/30 border-white/[0.08] hover:text-white/60"
                }`}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Principal (₹)">
            {(id) => (
              <input
                id={id}
                required
                type="number"
                min="0"
                step="0.01"
                value={form.principal}
                onChange={(e) => setForm({ ...form, principal: e.target.value })}
                placeholder="0"
                className={inputClass("rose")}
              />
            )}
          </Field>
          <Field label="EMI / month (₹)">
            {(id) => (
              <input
                id={id}
                required
                type="number"
                min="0"
                step="0.01"
                value={form.emiAmount}
                onChange={(e) => setForm({ ...form, emiAmount: e.target.value })}
                placeholder="0"
                className={inputClass("rose")}
              />
            )}
          </Field>
        </div>

        <Field label="Interest Rate (%)">
          {(id) => (
            <input
              id={id}
              required
              type="number"
              min="0"
              step="0.01"
              value={form.interestRate}
              onChange={(e) => setForm({ ...form, interestRate: e.target.value })}
              placeholder="e.g. 12.5"
              className={inputClass("rose")}
            />
          )}
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Start Date">
            {(id) => (
              <input
                id={id}
                required
                type="date"
                value={form.startDate}
                onChange={(e) => setForm({ ...form, startDate: e.target.value })}
                className={inputClass("rose")}
                style={{ colorScheme: "dark" }}
              />
            )}
          </Field>
          <Field label="End Date">
            {(id) => (
              <input
                id={id}
                required
                type="date"
                value={form.endDate}
                onChange={(e) => setForm({ ...form, endDate: e.target.value })}
                className={inputClass("rose")}
                style={{ colorScheme: "dark" }}
              />
            )}
          </Field>
        </div>

        <div className="flex items-center justify-between py-2">
          <span className="text-sm text-white/60">Loan is currently active</span>
          <button
            type="button"
            role="switch"
            aria-checked={form.active}
            aria-label="Loan is currently active"
            onClick={() => setForm({ ...form, active: !form.active })}
            className={`w-11 h-6 rounded-full transition-all relative ${
              form.active ? "bg-[#fb7185]" : "bg-white/10"
            }`}
          >
            <span
              className={`absolute top-1 w-4 h-4 rounded-full bg-white transition-all ${
                form.active ? "left-6" : "left-1"
              }`}
            />
          </button>
        </div>

        <ErrorBanner message={error} className="" />
        <FormActions accent="rose" loading={loading} submitLabel="Add Loan" onCancel={onClose} />
      </form>
    </ModalShell>
  );
}
