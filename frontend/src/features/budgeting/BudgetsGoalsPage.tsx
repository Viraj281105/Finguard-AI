import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { clearToken } from "../../shared/auth/tokenStorage";
import ErrorBanner from "../../shared/components/ErrorBanner";
import FullPageSpinner from "../../shared/components/Spinner";
import { authAPI } from "../auth/api";
import { budgetAPI, goalAPI } from "./api";
import AddBudgetModal from "./components/AddBudgetModal";
import AddContributionModal from "./components/AddContributionModal";
import AddGoalModal from "./components/AddGoalModal";
import BudgetSummaryCard from "./components/BudgetSummaryCard";
import CategoryBudgetCard from "./components/CategoryBudgetCard";
import GoalCard from "./components/GoalCard";
import GoalContributionsModal from "./components/GoalContributionsModal";
import MonthSelector from "./components/MonthSelector";
import type {
  CategoryBudgetPerformance,
  Goal,
  GoalStatus,
  MonthlyBudgetOverview,
} from "./types";

const NAV_LINKS = [
  { label: "Dashboard", to: "/dashboard" },
  { label: "Transactions", to: "/transactions" },
  { label: "Budgets & Goals", to: "/budgets" },
  { label: "Forecasting", to: "/forecasting" },
  { label: "Investments", to: "/investments" },
  { label: "Loans", to: "/loans" },
];

export default function BudgetsGoalsPage() {
  const navigate = useNavigate();

  // Active view tab
  const [activeTab, setActiveTab] = useState<"budgets" | "goals">("budgets");

  // Current selected month for budgeting (YYYY-MM)
  const [currentMonth, setCurrentMonth] = useState<string>(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
  });

  // Budgeting state
  const [budgetOverview, setBudgetOverview] = useState<MonthlyBudgetOverview | null>(null);
  const [budgetLoading, setBudgetLoading] = useState<boolean>(true);
  const [budgetError, setBudgetError] = useState<string>("");
  const [showAddBudgetModal, setShowAddBudgetModal] = useState<boolean>(false);
  const [editingBudget, setEditingBudget] = useState<CategoryBudgetPerformance | { budgetId: string; amount: number; category?: string | null } | null>(null);

  // Goals state
  const [goals, setGoals] = useState<Goal[]>([]);
  const [goalsLoading, setGoalsLoading] = useState<boolean>(true);
  const [goalsError, setGoalsError] = useState<string>("");
  const [goalStatusFilter, setGoalStatusFilter] = useState<GoalStatus | "ALL">("ALL");
  const [showAddGoalModal, setShowAddGoalModal] = useState<boolean>(false);
  const [editingGoal, setEditingGoal] = useState<Goal | null>(null);
  const [contributingGoal, setContributingGoal] = useState<Goal | null>(null);
  const [historyGoal, setHistoryGoal] = useState<Goal | null>(null);

  // Load budgets for selected month
  const loadBudgets = async (month: string) => {
    setBudgetLoading(true);
    setBudgetError("");
    try {
      const data = await budgetAPI.getOverview(month);
      setBudgetOverview(data);
    } catch {
      setBudgetError("Failed to load budget overview for " + month);
    } finally {
      setBudgetLoading(false);
    }
  };

  // Load goals
  const loadGoals = useCallback(async () => {
    setGoalsLoading(true);
    setGoalsError("");
    try {
      const statusParam = goalStatusFilter === "ALL" ? undefined : goalStatusFilter;
      const data = await goalAPI.listGoals(statusParam);
      setGoals(data);
    } catch {
      setGoalsError("Failed to load financial goals.");
    } finally {
      setGoalsLoading(false);
    }
  }, [goalStatusFilter]);

  useEffect(() => {
    loadBudgets(currentMonth);
  }, [currentMonth]);

  useEffect(() => {
    loadGoals();
  }, [loadGoals]);

  const handleLogout = async () => {
    try {
      await authAPI.logout();
    } finally {
      clearToken();
      navigate("/login");
    }
  };

  // Budget Handlers
  const handleEditBudget = (item: CategoryBudgetPerformance) => {
    setEditingBudget(item);
    setShowAddBudgetModal(true);
  };

  const handleEditOverallBudget = () => {
    if (budgetOverview?.overallBudget) {
      setEditingBudget({
        budgetId: budgetOverview.overallBudget.budgetId,
        amount: budgetOverview.overallBudget.budgetedAmount,
        category: null,
      });
    } else {
      setEditingBudget(null);
    }
    setShowAddBudgetModal(true);
  };

  const handleDeleteBudget = async (budgetId: string) => {
    if (!window.confirm("Are you sure you want to delete this budget?")) return;
    try {
      await budgetAPI.delete(budgetId);
      await loadBudgets(currentMonth);
    } catch {
      setBudgetError("Failed to delete budget.");
    }
  };

  // Goal Handlers
  const handleEditGoal = (goal: Goal) => {
    setEditingGoal(goal);
    setShowAddGoalModal(true);
  };

  const handleArchiveGoal = async (goal: Goal) => {
    if (!window.confirm(`Archive "${goal.name}"? You can still view it in archived goals.`)) return;
    try {
      await goalAPI.update(goal.id, { status: "ARCHIVED" });
      await loadGoals();
    } catch {
      setGoalsError("Failed to archive goal.");
    }
  };

  const handleDeleteGoal = async (goalId: string) => {
    if (!window.confirm("Permanently delete this financial goal and all recorded contributions?")) return;
    try {
      await goalAPI.delete(goalId);
      await loadGoals();
    } catch {
      setGoalsError("Failed to delete goal.");
    }
  };

  const existingCategories = budgetOverview?.categoryBudgets.map((b) => b.category) || [];

  return (
    <div className="min-h-screen bg-slate-50 text-slate-800 font-sans antialiased">
      {/* Top Navigation */}
      <nav className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/95 backdrop-blur-md px-4 sm:px-6 py-3">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span
              onClick={() => navigate("/dashboard")}
              className="font-bold text-lg tracking-tight text-slate-900 cursor-pointer"
            >
              Fin<span className="text-sky-600">Guard</span>
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 ml-1.5">
                AI
              </span>
            </span>
          </div>

          <div className="flex items-center gap-1 sm:gap-2">
            {NAV_LINKS.map((link) => (
              <button
                key={link.to}
                onClick={() => navigate(link.to)}
                className={`px-3 py-1.5 text-xs font-semibold rounded-xl transition-colors ${
                  link.to === "/budgets"
                    ? "text-sky-700 bg-sky-50 border border-sky-200/60"
                    : "text-slate-600 hover:text-slate-900 hover:bg-slate-100"
                }`}
              >
                {link.label}
              </button>
            ))}
            <button
              onClick={handleLogout}
              className="ml-2 sm:ml-4 px-3 py-1.5 text-xs font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors border border-slate-200/60"
            >
              Logout
            </button>
          </div>
        </div>
      </nav>

      {/* Main Content Area */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 py-8">
        {/* Page Header */}
        <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-slate-900">
              Budgets & Financial Goals
            </h1>
            <p className="text-slate-500 text-xs sm:text-sm mt-1">
              Active monthly spending controls, category budget tracking, and objective savings milestones
            </p>
          </div>

          {/* Tab Selector */}
          <div className="bg-slate-200/70 p-1 rounded-xl flex items-center gap-1 text-xs font-semibold">
            <button
              onClick={() => setActiveTab("budgets")}
              className={`px-4 py-1.5 rounded-lg transition-all ${
                activeTab === "budgets"
                  ? "bg-white text-slate-900 shadow-2xs font-bold"
                  : "text-slate-600 hover:text-slate-900"
              }`}
            >
              Monthly Budgets
            </button>
            <button
              onClick={() => setActiveTab("goals")}
              className={`px-4 py-1.5 rounded-lg transition-all ${
                activeTab === "goals"
                  ? "bg-white text-slate-900 shadow-2xs font-bold"
                  : "text-slate-600 hover:text-slate-900"
              }`}
            >
              Financial Goals
            </button>
          </div>
        </div>

        {/* TAB 1: BUDGETS */}
        {activeTab === "budgets" && (
          <div className="space-y-6">
            {/* Month Selector */}
            <MonthSelector
              currentMonth={currentMonth}
              onMonthChange={(m) => setCurrentMonth(m)}
            />

            <ErrorBanner message={budgetError} />

            {/* Contextual Warnings */}
            {budgetOverview && budgetOverview.warnings.length > 0 && (
              <div className="space-y-2">
                {budgetOverview.warnings.map((w, idx) => (
                  <div
                    key={idx}
                    className="bg-amber-50 border border-amber-200/80 p-3 rounded-xl text-xs text-amber-800 flex items-center gap-2"
                  >
                    <span className="font-bold">⚠️ Warning:</span> {w}
                  </div>
                ))}
              </div>
            )}

            {budgetLoading ? (
              <div className="py-16 flex justify-center">
                <FullPageSpinner accent="cyan" />
              </div>
            ) : budgetOverview ? (
              <>
                {/* Overall Budget Card */}
                <BudgetSummaryCard
                  overallBudget={budgetOverview.overallBudget}
                  totalActualExpenses={budgetOverview.totalActualExpenses}
                  totalCategoryBudgeted={budgetOverview.totalCategoryBudgeted}
                  unbudgetedSpending={budgetOverview.unbudgetedSpending}
                  onSetOverallBudget={handleEditOverallBudget}
                />

                {/* Category Budgets Section */}
                <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-xs">
                  <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                    <div>
                      <h2 className="text-sm font-bold text-slate-900">Category Budgets</h2>
                      <p className="text-xs text-slate-500 mt-0.5">
                        Track and control spending across specific expense categories
                      </p>
                    </div>

                    <button
                      onClick={() => {
                        setEditingBudget(null);
                        setShowAddBudgetModal(true);
                      }}
                      className="px-4 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 rounded-xl transition-colors shadow-2xs"
                    >
                      + Add Category Budget
                    </button>
                  </div>

                  {budgetOverview.categoryBudgets.length === 0 ? (
                    <div className="text-center py-10 border border-dashed border-slate-200 rounded-xl">
                      <p className="text-sm font-semibold text-slate-700">
                        No category budgets configured for {currentMonth}
                      </p>
                      <p className="text-xs text-slate-400 mt-1 max-w-sm mx-auto">
                        Create monthly limits for variable categories like Groceries, Dining, or Transport to monitor expenditure.
                      </p>
                      <button
                        onClick={() => {
                          setEditingBudget(null);
                          setShowAddBudgetModal(true);
                        }}
                        className="mt-3 px-4 py-1.5 text-xs font-semibold text-sky-600 hover:text-sky-700 bg-sky-50 hover:bg-sky-100 rounded-xl border border-sky-200 transition-colors"
                      >
                        + Set First Category Budget
                      </button>
                    </div>
                  ) : (
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                      {budgetOverview.categoryBudgets.map((item) => (
                        <CategoryBudgetCard
                          key={item.budgetId}
                          item={item}
                          onEdit={handleEditBudget}
                          onDelete={handleDeleteBudget}
                        />
                      ))}
                    </div>
                  )}
                </div>
              </>
            ) : null}
          </div>
        )}

        {/* TAB 2: FINANCIAL GOALS */}
        {activeTab === "goals" && (
          <div className="space-y-6">
            {/* Top Action & Notice */}
            <div className="flex flex-wrap items-center justify-between gap-4">
              {/* Filter Pills */}
              <div className="flex items-center gap-1.5 bg-white p-1 rounded-xl border border-slate-200 text-xs font-semibold">
                {(["ALL", "ACTIVE", "COMPLETED", "ARCHIVED"] as const).map((filter) => (
                  <button
                    key={filter}
                    onClick={() => setGoalStatusFilter(filter)}
                    className={`px-3 py-1 rounded-lg transition-colors capitalize ${
                      goalStatusFilter === filter
                        ? "bg-slate-900 text-white font-bold"
                        : "text-slate-600 hover:text-slate-900 hover:bg-slate-100"
                    }`}
                  >
                    {filter === "ALL" ? "All Goals" : filter.toLowerCase()}
                  </button>
                ))}
              </div>

              <button
                onClick={() => {
                  setEditingGoal(null);
                  setShowAddGoalModal(true);
                }}
                className="px-4 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 rounded-xl transition-colors shadow-2xs"
              >
                + New Financial Goal
              </button>
            </div>

            {/* Disclaimer Box */}
            <div className="bg-sky-50/70 border border-sky-200/80 p-3.5 rounded-2xl text-xs text-sky-900 flex items-start gap-2.5">
              <span className="text-base leading-none">ℹ️</span>
              <p>
                <strong>Personal Finance Note:</strong> Goal contributions are tracked for planning purposes and do not represent verified bank balances or automated account transfers.
              </p>
            </div>

            <ErrorBanner message={goalsError} />

            {goalsLoading ? (
              <div className="py-16 flex justify-center">
                <FullPageSpinner accent="cyan" />
              </div>
            ) : goals.length === 0 ? (
              <div className="bg-white p-12 rounded-2xl border border-slate-200/80 text-center shadow-xs">
                <h3 className="text-base font-bold text-slate-800">No Financial Goals Found</h3>
                <p className="text-xs text-slate-500 mt-1 max-w-md mx-auto">
                  Set up savings targets for emergencies, big purchases, vacations, or debt freedom. Record contributions as you save.
                </p>
                <button
                  onClick={() => {
                    setEditingGoal(null);
                    setShowAddGoalModal(true);
                  }}
                  className="mt-4 px-4 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 rounded-xl transition-colors shadow-2xs"
                >
                  + Create Your First Goal
                </button>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
                {goals.map((goal) => (
                  <GoalCard
                    key={goal.id}
                    goal={goal}
                    onContribute={(g) => setContributingGoal(g)}
                    onViewHistory={(g) => setHistoryGoal(g)}
                    onEdit={handleEditGoal}
                    onArchive={handleArchiveGoal}
                    onDelete={handleDeleteGoal}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </main>

      {/* Modals */}
      {showAddBudgetModal && (
        <AddBudgetModal
          currentMonth={currentMonth}
          editingBudget={editingBudget}
          existingBudgetedCategories={existingCategories}
          onClose={() => {
            setShowAddBudgetModal(false);
            setEditingBudget(null);
          }}
          onSaved={async () => {
            await loadBudgets(currentMonth);
          }}
        />
      )}

      {showAddGoalModal && (
        <AddGoalModal
          editingGoal={editingGoal}
          onClose={() => {
            setShowAddGoalModal(false);
            setEditingGoal(null);
          }}
          onSaved={async () => {
            await loadGoals();
          }}
        />
      )}

      {contributingGoal && (
        <AddContributionModal
          goal={contributingGoal}
          onClose={() => setContributingGoal(null)}
          onAdded={async () => {
            await loadGoals();
          }}
        />
      )}

      {historyGoal && (
        <GoalContributionsModal
          goal={historyGoal}
          onClose={() => setHistoryGoal(null)}
          onContributionDeleted={async () => {
            await loadGoals();
          }}
        />
      )}
    </div>
  );
}
