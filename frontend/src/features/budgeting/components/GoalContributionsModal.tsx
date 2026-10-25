import { useCallback, useEffect, useState } from "react";
import ErrorBanner from "../../../shared/components/ErrorBanner";
import FullPageSpinner from "../../../shared/components/Spinner";
import { formatCurrency, formatDate } from "../../../shared/utils/format";
import { goalAPI } from "../api";
import type { Goal, GoalDetail } from "../types";

interface GoalContributionsModalProps {
  goal: Goal;
  onClose: () => void;
  onContributionDeleted: () => void;
}

export default function GoalContributionsModal({
  goal,
  onClose,
  onContributionDeleted,
}: GoalContributionsModalProps) {
  const [detail, setDetail] = useState<GoalDetail | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>("");
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const fetchDetail = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const data = await goalAPI.getDetail(goal.id);
      setDetail(data);
    } catch {
      setError("Failed to load contribution history.");
    } finally {
      setLoading(false);
    }
  }, [goal.id]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const handleDelete = async (contribId: string) => {
    if (!window.confirm("Remove this recorded contribution?")) return;
    setDeletingId(contribId);
    try {
      await goalAPI.deleteContribution(goal.id, contribId);
      onContributionDeleted();
      await fetchDetail();
    } catch {
      setError("Failed to delete contribution.");
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-xs">
      <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-slate-200 flex flex-col max-h-[85vh]">
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <div>
            <h2 className="text-base font-bold text-slate-900">Savings Contribution History</h2>
            <p className="text-xs text-slate-500 mt-0.5">{goal.name}</p>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-600 text-lg leading-none p-1"
          >
            ✕
          </button>
        </div>

        <ErrorBanner message={error} className="my-3" />

        <div className="flex-1 overflow-y-auto py-3 space-y-2">
          {loading ? (
            <div className="py-8 flex justify-center">
              <FullPageSpinner accent="cyan" />
            </div>
          ) : !detail || detail.contributions.length === 0 ? (
            <div className="text-center py-8">
              <p className="text-sm font-semibold text-slate-700">No contributions recorded yet</p>
              <p className="text-xs text-slate-400 mt-1">
                Record savings deposits toward this goal to track your milestone progress.
              </p>
            </div>
          ) : (
            detail.contributions.map((c) => (
              <div
                key={c.id}
                className="flex items-center justify-between p-3 rounded-xl bg-slate-50 border border-slate-200/60"
              >
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-bold text-slate-900">
                      {formatCurrency(c.amount)}
                    </span>
                    <span className="text-xs text-slate-500 font-mono">
                      {formatDate(c.contributionDate)}
                    </span>
                  </div>
                  {c.note && (
                    <p className="text-xs text-slate-600 mt-0.5">{c.note}</p>
                  )}
                </div>

                <button
                  onClick={() => handleDelete(c.id)}
                  disabled={deletingId === c.id}
                  className="text-xs text-slate-400 hover:text-rose-600 p-1.5 rounded-lg hover:bg-rose-50 transition-colors"
                  title="Remove contribution"
                >
                  {deletingId === c.id ? "Removing..." : "Remove"}
                </button>
              </div>
            ))
          )}
        </div>

        {/* Disclaimer footer */}
        <div className="pt-3 border-t border-slate-100">
          <p className="text-[11px] text-slate-400 italic mb-3">
            {detail?.disclaimer ||
              "Goal contributions are tracked for planning purposes and do not represent verified bank balances or automated account transfers."}
          </p>
          <div className="flex justify-end">
            <button
              onClick={onClose}
              className="px-4 py-2 text-xs font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors"
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
