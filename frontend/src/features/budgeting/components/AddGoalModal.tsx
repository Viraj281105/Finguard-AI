import { useState } from "react";
import ErrorBanner from "../../../shared/components/ErrorBanner";
import { goalAPI } from "../api";
import type { Goal } from "../types";

interface AddGoalModalProps {
  editingGoal?: Goal | null;
  onClose: () => void;
  onSaved: (goal: Goal) => void;
}

export default function AddGoalModal({ editingGoal, onClose, onSaved }: AddGoalModalProps) {
  const isEditing = !!editingGoal;

  const [name, setName] = useState<string>(editingGoal?.name || "");
  const [description, setDescription] = useState<string>(editingGoal?.description || "");
  const [targetAmount, setTargetAmount] = useState<string>(
    editingGoal ? String(editingGoal.targetAmount) : ""
  );
  const [targetDate, setTargetDate] = useState<string>(editingGoal?.targetDate || "");
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string>("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const parsedTarget = parseFloat(targetAmount);
    if (isNaN(parsedTarget) || parsedTarget <= 0) {
      setError("Target amount must be a positive number.");
      return;
    }

    if (!name.trim()) {
      setError("Please provide a name for this goal.");
      return;
    }

    setLoading(true);
    try {
      if (isEditing && editingGoal) {
        const updated = await goalAPI.update(editingGoal.id, {
          name: name.trim(),
          description: description.trim() || undefined,
          targetAmount: parsedTarget,
          targetDate: targetDate || undefined,
        });
        onSaved(updated);
      } else {
        const created = await goalAPI.create({
          name: name.trim(),
          description: description.trim() || undefined,
          targetAmount: parsedTarget,
          targetDate: targetDate || undefined,
        });
        onSaved(created);
      }
      onClose();
    } catch (err: unknown) {
      if (typeof err === "object" && err !== null && "response" in err) {
        const axiosError = err as { response?: { data?: { message?: string } } };
        setError(axiosError.response?.data?.message || "Failed to save goal.");
      } else {
        setError("Failed to save goal. Please check your inputs.");
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
            {isEditing ? "Edit Savings Goal" : "Create New Financial Goal"}
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
          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Goal Name
            </label>
            <input
              type="text"
              placeholder="e.g. Emergency Fund, Vacation, Car Downpayment"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              className="w-full text-sm bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-900 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Target Amount (₹)
            </label>
            <input
              type="number"
              step="0.01"
              min="0.01"
              placeholder="e.g. 100000"
              value={targetAmount}
              onChange={(e) => setTargetAmount(e.target.value)}
              required
              className="w-full text-sm bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-900 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Target Date (Optional)
            </label>
            <input
              type="date"
              value={targetDate}
              onChange={(e) => setTargetDate(e.target.value)}
              className="w-full text-xs bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
            <p className="text-[11px] text-slate-400 mt-1">
              Setting a target date enables monthly savings pace tracking.
            </p>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Description / Notes (Optional)
            </label>
            <textarea
              rows={2}
              placeholder="e.g. 3 months of essential fixed living expenses"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full text-xs bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
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
              {loading ? "Saving..." : isEditing ? "Save Changes" : "Create Goal"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
