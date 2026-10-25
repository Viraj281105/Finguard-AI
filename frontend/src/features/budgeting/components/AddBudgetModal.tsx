import { useState } from "react";
import ErrorBanner from "../../../shared/components/ErrorBanner";
import { budgetAPI } from "../api";
import type { Budget, CategoryBudgetPerformance } from "../types";

const EXPENSE_CATEGORIES = [
  { id: "GROCERIES", label: "Groceries" },
  { id: "DINING", label: "Dining & Food" },
  { id: "TRANSPORT", label: "Transport & Fuel" },
  { id: "HOUSING", label: "Housing & Rent" },
  { id: "UTILITIES", label: "Utilities" },
  { id: "HEALTHCARE", label: "Healthcare & Medical" },
  { id: "INSURANCE", label: "Insurance" },
  { id: "SHOPPING", label: "Shopping" },
  { id: "SUBSCRIPTIONS", label: "Subscriptions" },
  { id: "EDUCATION", label: "Education" },
  { id: "ENTERTAINMENT", label: "Entertainment" },
  { id: "FEES", label: "Fees & Charges" },
  { id: "LOAN_PAYMENTS", label: "Loan Payments / EMI" },
  { id: "UNCATEGORIZED", label: "Uncategorized" },
];

interface AddBudgetModalProps {
  currentMonth: string; // YYYY-MM
  editingBudget?: CategoryBudgetPerformance | { budgetId: string; amount: number; category?: string | null } | null;
  existingBudgetedCategories: string[];
  onClose: () => void;
  onSaved: (budget: Budget) => void;
}

export default function AddBudgetModal({
  currentMonth,
  editingBudget,
  existingBudgetedCategories,
  onClose,
  onSaved,
}: AddBudgetModalProps) {
  const isEditing = !!editingBudget;
  const initialCategory = editingBudget
    ? "category" in editingBudget && editingBudget.category
      ? editingBudget.category
      : "OVERALL"
    : "OVERALL";

  const initialAmount = editingBudget
    ? "budgetedAmount" in editingBudget
      ? String(editingBudget.budgetedAmount)
      : String(editingBudget.amount)
    : "";

  const [scope, setScope] = useState<string>(initialCategory);
  const [amount, setAmount] = useState<string>(initialAmount);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string>("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const parsedAmount = parseFloat(amount);
    if (isNaN(parsedAmount) || parsedAmount <= 0) {
      setError("Please enter a valid amount greater than zero.");
      return;
    }

    setLoading(true);
    try {
      if (isEditing && editingBudget) {
        const updated = await budgetAPI.update(editingBudget.budgetId, {
          amount: parsedAmount,
        });
        onSaved(updated);
      } else {
        const categoryVal = scope === "OVERALL" ? null : scope;
        const created = await budgetAPI.create({
          yearMonth: currentMonth,
          category: categoryVal,
          amount: parsedAmount,
        });
        onSaved(created);
      }
      onClose();
    } catch (err: unknown) {
      if (typeof err === "object" && err !== null && "response" in err) {
        const axiosError = err as { response?: { data?: { message?: string } } };
        setError(axiosError.response?.data?.message || "Failed to save budget.");
      } else {
        setError("Failed to save budget. Please check your inputs.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-xs">
      <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-slate-200">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-bold text-slate-900">
            {isEditing ? "Edit Budget Limit" : `Set Budget for ${currentMonth}`}
          </h2>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-600 text-lg leading-none p-1"
          >
            ✕
          </button>
        </div>

        <ErrorBanner message={error} className="mb-4" />

        <form onSubmit={handleSubmit} className="space-y-4">
          {!isEditing ? (
            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                Budget Scope
              </label>
              <select
                value={scope}
                onChange={(e) => setScope(e.target.value)}
                className="w-full text-xs bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
              >
                <option value="OVERALL">Overall Monthly Spending Cap</option>
                <optgroup label="Spending Categories">
                  {EXPENSE_CATEGORIES.map((cat) => {
                    const alreadyBudgeted = existingBudgetedCategories.includes(cat.id);
                    return (
                      <option key={cat.id} value={cat.id} disabled={alreadyBudgeted}>
                        {cat.label} {alreadyBudgeted ? "(Already Set)" : ""}
                      </option>
                    );
                  })}
                </optgroup>
              </select>
              <p className="text-[11px] text-slate-400 mt-1">
                Transfers, investments, and card payments are non-spending flows and excluded.
              </p>
            </div>
          ) : (
            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                Budget Item
              </label>
              <p className="text-sm font-bold text-slate-800 bg-slate-50 px-3 py-2 rounded-xl border border-slate-200">
                {scope === "OVERALL"
                  ? "Overall Monthly Spending Cap"
                  : EXPENSE_CATEGORIES.find((c) => c.id === scope)?.label || scope}
              </p>
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Monthly Budget Limit (₹)
            </label>
            <input
              type="number"
              step="0.01"
              min="0.01"
              placeholder="e.g. 15000"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
              className="w-full text-sm bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-900 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          <div className="flex items-center justify-end gap-2 pt-3 border-t border-slate-100">
            <button
              type="button"
              onClick={onClose}
              disabled={loading}
              className="px-4 py-2 text-xs font-semibold text-slate-600 hover:text-slate-800 hover:bg-slate-100 rounded-xl transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="px-4 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 disabled:opacity-50 rounded-xl transition-colors shadow-2xs"
            >
              {loading ? "Saving..." : isEditing ? "Update Limit" : "Set Budget"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
