import { useState } from "react";
import { apiErrorMessage } from "../../shared/api/client";
import { ACCENT_STYLES, inputClass } from "../../shared/components/accent";
import ErrorBanner from "../../shared/components/ErrorBanner";
import { Field, FormActions, ModalShell } from "../../shared/components/Modal";
import { investmentAPI } from "./api";
import type { Investment, InvestmentType } from "./types";

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
    } catch (err) {
      setError(apiErrorMessage(err, "Failed to create investment. Please try again."));
    } finally {
      setLoading(false);
    }
  };

  return (
    <ModalShell title="Add Investment" onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <Field label="Name">
          {(id) => (
            <input
              id={id}
              required
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. Nifty 50 Index Fund"
              className={inputClass("violet")}
            />
          )}
        </Field>

        <div>
          <p className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">Type</p>
          <div className="grid grid-cols-3 gap-2">
            {TYPES.map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setForm({ ...form, type: t })}
                className={`py-2 rounded-xl text-xs font-medium transition-all border ${
                  form.type === t
                    ? ACCENT_STYLES.violet.selected
                    : "text-white/30 border-white/[0.08] hover:text-white/60"
                }`}
              >
                {t.replace("_", " ")}
              </button>
            ))}
          </div>
        </div>

        <Field label="Amount Invested (₹)">
          {(id) => (
            <input
              id={id}
              required
              type="number"
              min="0"
              step="0.01"
              value={form.amountInvested}
              onChange={(e) => setForm({ ...form, amountInvested: e.target.value })}
              placeholder="0"
              className={inputClass("violet")}
            />
          )}
        </Field>

        <Field label="Current Value (₹)" hint="leave blank to match invested">
          {(id) => (
            <input
              id={id}
              type="number"
              min="0"
              step="0.01"
              value={form.currentValue}
              onChange={(e) => setForm({ ...form, currentValue: e.target.value })}
              placeholder={form.amountInvested || "0"}
              className={inputClass("violet")}
            />
          )}
        </Field>

        <Field label="Notes (optional)">
          {(id) => (
            <input
              id={id}
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
              placeholder="Any extra details..."
              className={inputClass("violet")}
            />
          )}
        </Field>

        <ErrorBanner message={error} className="" />
        <FormActions accent="violet" loading={loading} submitLabel="Add Investment" onCancel={onClose} />
      </form>
    </ModalShell>
  );
}
