import { useState } from "react";
import ErrorBanner from "../../../shared/components/ErrorBanner";
import { formatCurrency } from "../../../shared/utils/format";
import { goalAPI } from "../api";
import type { Goal, GoalContribution } from "../types";

interface AddContributionModalProps {
  goal: Goal;
  onClose: () => void;
  onAdded: (contribution: GoalContribution) => void;
}

export default function AddContributionModal({
  goal,
  onClose,
  onAdded,
}: AddContributionModalProps) {
  const [amount, setAmount] = useState<string>("");
  const [date, setDate] = useState<string>(
    new Date().toISOString().split("T")[0]
  );
  const [note, setNote] = useState<string>("");
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string>("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    const parsedAmount = parseFloat(amount);
    if (isNaN(parsedAmount) || parsedAmount <= 0) {
      setError("Please enter a valid contribution amount greater than zero.");
      return;
    }

    setLoading(true);
    try {
      const contrib = await goalAPI.addContribution(goal.id, {
        amount: parsedAmount,
        contributionDate: date || undefined,
        note: note.trim() || undefined,
      });
      onAdded(contrib);
      onClose();
    } catch (err: unknown) {
      if (typeof err === "object" && err !== null && "response" in err) {
        const axiosError = err as { response?: { data?: { message?: string } } };
        setError(axiosError.response?.data?.message || "Failed to record contribution.");
      } else {
        setError("Failed to record contribution. Please check your inputs.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-xs">
      <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-slate-200">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-base font-bold text-slate-900">Record Savings Contribution</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              Allocate savings toward <span className="font-semibold text-slate-800">{goal.name}</span>
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-600 text-lg leading-none p-1"
          >
            ✕
          </button>
        </div>

        <div className="bg-slate-50 p-3 rounded-xl border border-slate-200/60 mb-4 flex justify-between text-xs">
          <div>
            <span className="text-slate-400 text-[10px]">Current Progress</span>
            <p className="font-bold text-slate-800 mt-0.5">{formatCurrency(goal.currentAmount)}</p>
          </div>
          <div className="text-right">
            <span className="text-slate-400 text-[10px]">Target Goal</span>
            <p className="font-bold text-slate-900 mt-0.5">{formatCurrency(goal.targetAmount)}</p>
          </div>
        </div>

        <ErrorBanner message={error} className="mb-4" />

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Contribution Amount (₹)
            </label>
            <input
              type="number"
              step="0.01"
              min="0.01"
              placeholder="e.g. 5000"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
              className="w-full text-sm bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-900 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Contribution Date
            </label>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
              className="w-full text-xs bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Note (Optional)
            </label>
            <input
              type="text"
              placeholder="e.g. Monthly salary savings deposit"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              className="w-full text-xs bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-500/20 focus:border-sky-500"
            />
          </div>

          {/* Explicit Banking Disclaimer */}
          <div className="bg-amber-50/70 border border-amber-200/80 p-2.5 rounded-xl text-[11px] text-amber-800">
            <strong>Important:</strong> Recorded contributions track progress toward your goal. FinGuard-AI does not initiate real bank transfers or reserve cash.
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
              {loading ? "Recording..." : "Record Contribution"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
