import { useState } from "react";
import { apiErrorMessage } from "../../shared/api/client";
import { categoriesForType } from "../../shared/categories";
import { inputClass } from "../../shared/components/accent";
import ErrorBanner from "../../shared/components/ErrorBanner";
import { Field, FormActions, ModalShell } from "../../shared/components/Modal";
import { transactionAPI } from "./api";
import type { Transaction, TransactionType } from "./types";

interface Props {
  onClose: () => void;
  onAdded: (tx: Transaction) => void;
}

export default function AddTransactionModal({ onClose, onAdded }: Props) {
  const [form, setForm] = useState({
    title: "",
    amount: "",
    type: "INCOME" as TransactionType,
    category: "INCOME",
    note: "",
    date: new Date().toISOString().split("T")[0],
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
        category: form.category || undefined,
        note: form.note,
        date: form.date,
      });
      onAdded(created);
      onClose();
    } catch (err) {
      setError(apiErrorMessage(err, "Failed to create transaction. Please try again."));
    } finally {
      setLoading(false);
    }
  };

  return (
    <ModalShell title="Add Transaction" onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Type toggle */}
        <div className="flex rounded-xl overflow-hidden border border-white/[0.08]">
          {(["INCOME", "EXPENSE"] as TransactionType[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() =>
                setForm({
                  ...form,
                  type: t,
                  // Keep the category only if it still suits the new direction.
                  category: categoriesForType(t).some((c) => c.id === form.category) ? form.category : "",
                })
              }
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

        <Field label="Title">
          {(id) => (
            <input
              id={id}
              required
              value={form.title}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
              placeholder="e.g. March Salary"
              className={inputClass("cyan")}
            />
          )}
        </Field>

        <Field label="Amount (₹)">
          {(id) => (
            <input
              id={id}
              required
              type="number"
              min="0"
              step="0.01"
              value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value })}
              placeholder="0"
              className={inputClass("cyan")}
            />
          )}
        </Field>

        <Field label="Date">
          {(id) => (
            <input
              id={id}
              required
              type="date"
              value={form.date}
              onChange={(e) => setForm({ ...form, date: e.target.value })}
              className={inputClass("cyan")}
            />
          )}
        </Field>

        <Field label="Category">
          {(id) => (
            <select
              id={id}
              value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })}
              className={inputClass("cyan", "select")}
            >
              <option value="">No category yet</option>
              {categoriesForType(form.type).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          )}
        </Field>

        <Field label="Note (optional)">
          {(id) => (
            <input
              id={id}
              value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })}
              placeholder="Any extra details..."
              className={inputClass("cyan")}
            />
          )}
        </Field>

        <ErrorBanner message={error} className="" />
        <FormActions accent="cyan" loading={loading} submitLabel="Add Transaction" onCancel={onClose} />
      </form>
    </ModalShell>
  );
}
