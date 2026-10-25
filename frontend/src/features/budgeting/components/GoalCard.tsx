import { formatCurrency } from "../../../shared/utils/format";
import type { Goal } from "../types";

interface GoalCardProps {
  goal: Goal;
  onContribute: (goal: Goal) => void;
  onViewHistory: (goal: Goal) => void;
  onEdit: (goal: Goal) => void;
  onArchive: (goal: Goal) => void;
  onDelete: (goalId: string) => void;
}

export default function GoalCard({
  goal,
  onContribute,
  onViewHistory,
  onEdit,
  onArchive,
  onDelete,
}: GoalCardProps) {
  const {
    name,
    description,
    targetAmount,
    currentAmount,
    remainingAmount,
    progressPercentage,
    targetDate,
    daysRemaining,
    requiredMonthlyPace,
    status,
    isOverdue,
    contributionCount,
  } = goal;

  const isCompleted = status === "COMPLETED" || currentAmount >= targetAmount;
  const isArchived = status === "ARCHIVED";

  const getStatusBadge = () => {
    if (isCompleted) {
      return {
        bg: "bg-emerald-50 text-emerald-700 border-emerald-200",
        label: "Goal Completed!",
        barColor: "bg-emerald-500",
      };
    }
    if (isOverdue) {
      return {
        bg: "bg-rose-50 text-rose-700 border-rose-200",
        label: "Target Date Passed",
        barColor: "bg-rose-500",
      };
    }
    if (isArchived) {
      return {
        bg: "bg-slate-100 text-slate-600 border-slate-200",
        label: "Archived",
        barColor: "bg-slate-400",
      };
    }
    return {
      bg: "bg-sky-50 text-sky-700 border-sky-200",
      label: "Active Target",
      barColor: "bg-sky-500",
    };
  };

  const badge = getStatusBadge();
  const cappedPercentage = Math.min(progressPercentage, 100);

  return (
    <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-2xs hover:shadow-xs transition-shadow flex flex-col justify-between">
      <div>
        {/* Header */}
        <div className="flex items-start justify-between gap-3 mb-2">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-sm font-bold text-slate-900">{name}</h3>
              <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border ${badge.bg}`}>
                {badge.label}
              </span>
            </div>
            {description && (
              <p className="text-xs text-slate-500 mt-0.5 line-clamp-2">{description}</p>
            )}
          </div>

          <div className="flex items-center gap-1">
            <button
              onClick={() => onEdit(goal)}
              className="text-slate-400 hover:text-slate-700 p-1 rounded-lg text-xs"
              title="Edit Goal"
            >
              Edit
            </button>
            {!isArchived ? (
              <button
                onClick={() => onArchive(goal)}
                className="text-slate-400 hover:text-slate-700 p-1 rounded-lg text-xs"
                title="Archive Goal"
              >
                Archive
              </button>
            ) : null}
            <button
              onClick={() => onDelete(goal.id)}
              className="text-slate-400 hover:text-rose-600 p-1 rounded-lg text-xs"
              title="Delete Goal"
            >
              Delete
            </button>
          </div>
        </div>

        {/* Progress Bar */}
        <div className="space-y-1.5 my-3">
          <div className="w-full bg-slate-100 rounded-full h-3 overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-300 ${badge.barColor}`}
              style={{ width: `${cappedPercentage}%` }}
            />
          </div>
          <div className="flex justify-between items-center text-xs">
            <span className="font-semibold text-slate-700">
              {progressPercentage.toFixed(1)}% saved
            </span>
            <span className="text-slate-500">
              {remainingAmount > 0
                ? `${formatCurrency(remainingAmount)} remaining`
                : "Target met"}
            </span>
          </div>
        </div>

        {/* Financial Details Grid */}
        <div className="grid grid-cols-2 gap-3 py-3 border-t border-slate-100 text-xs">
          <div>
            <span className="text-slate-400 text-[10px]">Saved Progress</span>
            <p className="font-extrabold text-slate-900 mt-0.5">{formatCurrency(currentAmount)}</p>
          </div>
          <div>
            <span className="text-slate-400 text-[10px]">Target Goal</span>
            <p className="font-extrabold text-slate-800 mt-0.5">{formatCurrency(targetAmount)}</p>
          </div>
        </div>

        {/* Target Date & Pace Info */}
        {(targetDate || requiredMonthlyPace) && (
          <div className="bg-slate-50 p-2.5 rounded-xl border border-slate-200/60 text-xs space-y-1 mb-4">
            {targetDate && (
              <div className="flex justify-between text-[11px]">
                <span className="text-slate-500">Target Date:</span>
                <span className="font-medium text-slate-700">
                  {targetDate} {daysRemaining !== undefined && daysRemaining !== null && `(${daysRemaining > 0 ? `${daysRemaining} days left` : "Overdue"})`}
                </span>
              </div>
            )}
            {requiredMonthlyPace && !isCompleted && !isOverdue && (
              <div className="flex justify-between text-[11px]">
                <span className="text-slate-500">Required Pace:</span>
                <span className="font-bold text-sky-700">
                  {formatCurrency(requiredMonthlyPace)} / mo
                </span>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Action Buttons */}
      <div className="flex items-center justify-between gap-2 pt-3 border-t border-slate-100 mt-auto">
        <button
          onClick={() => onViewHistory(goal)}
          className="text-xs font-medium text-slate-500 hover:text-slate-800 transition-colors"
        >
          {contributionCount} {contributionCount === 1 ? "Contribution" : "Contributions"}
        </button>

        {!isArchived && !isCompleted && (
          <button
            onClick={() => onContribute(goal)}
            className="px-3.5 py-1.5 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 rounded-xl transition-colors shadow-2xs"
          >
            + Record Savings
          </button>
        )}
      </div>
    </div>
  );
}
