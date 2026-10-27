import { useCallback, useEffect, useState } from "react";
import { countsTowardTotals } from "../../shared/categories";
import ErrorBanner from "../../shared/components/ErrorBanner";
import PageNav, { AddButton } from "../../shared/components/PageNav";
import FullPageSpinner from "../../shared/components/Spinner";
import { formatCurrency, formatDate } from "../../shared/utils/format";
import AddTransactionModal from "./AddTransactionModal";
import { transactionAPI } from "./api";
import CategoryCell from "./CategoryCell";
import { describeReclassify } from "./describeReclassify";
import type { PagedTransactions, Transaction } from "./types";

type Filter = "ALL" | "INCOME" | "EXPENSE";
/** Which categorisation state to list: everything, rows without a category, or automatic rows to check. */
type CategoryFilter = "ALL" | "UNCATEGORIZED" | "REVIEW";

const CATEGORY_FILTERS: { id: CategoryFilter; label: string }[] = [
  { id: "ALL", label: "All categories" },
  { id: "UNCATEGORIZED", label: "Needs a category" },
  { id: "REVIEW", label: "Needs review" },
];

const isUncategorised = (t: Transaction) => !t.category || t.category === "UNCATEGORIZED";
const needsReview = (t: Transaction) =>
  t.categoryNeedsReview === true && (t.categorySource === "RULE" || t.categorySource === "MODEL");

const PAGE_SIZE = 20;
const LOAD_ERROR = "Could not fetch transactions.";
const CATEGORY_ERROR = "Could not save the category. Please try again.";
const RECLASSIFY_ERROR = "Could not reach the server to categorize. Please try again.";

export default function TransactionsPage() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>("ALL");
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>("ALL");
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [reclassifying, setReclassifying] = useState(false);
  const [notice, setNotice] = useState("");

  const fetchTransactions = useCallback(async (
    targetPage: number,
    currentFilter: Filter,
    currentCategoryFilter: CategoryFilter,
  ) => {
    setError("");
    setLoading(true);
    try {
      const query = {
        page: targetPage,
        size: PAGE_SIZE,
        type: currentFilter === "ALL" ? undefined : currentFilter,
        category: currentCategoryFilter === "UNCATEGORIZED" ? "UNCATEGORIZED" : undefined,
        needsReview: currentCategoryFilter === "REVIEW" ? true : undefined,
      };

      if (typeof transactionAPI.getPage === "function") {
        const paged: PagedTransactions = await transactionAPI.getPage(query);
        setTransactions(paged.data);
        setTotalCount(paged.totalCount);
        setTotalPages(Math.max(1, paged.totalPages));
      } else {
        const list = await transactionAPI.getAll(query);
        setTransactions(list);
        setTotalCount(list.length);
        setTotalPages(Math.max(1, Math.ceil(list.length / PAGE_SIZE)));
      }
    } catch {
      setError(LOAD_ERROR);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTransactions(page, filter, categoryFilter);
  }, [page, filter, categoryFilter, fetchTransactions]);

  const handleFilterChange = (f: Filter) => {
    setFilter(f);
    setPage(0);
  };

  const handleCategoryFilterChange = (f: CategoryFilter) => {
    setCategoryFilter(f);
    setPage(0);
  };

  /** Saves the user's own category (a correction, or a confirmation of an automatic one). */
  const handleCategory = async (tx: Transaction, category: string) => {
    setActionError("");
    setNotice("");
    setSavingId(tx.id);
    try {
      const updated = await transactionAPI.updateCategory(tx.id, category);
      setTransactions((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
    } catch (err) {
      setActionError(CATEGORY_ERROR);
      throw err;
    } finally {
      setSavingId(null);
    }
  };

  const handleReclassify = async () => {
    setActionError("");
    setNotice("");
    setReclassifying(true);
    try {
      const result = await transactionAPI.reclassify();
      setNotice(describeReclassify(result));
      await fetchTransactions(page, filter, categoryFilter);
    } catch {
      setActionError(RECLASSIFY_ERROR);
    } finally {
      setReclassifying(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this transaction?")) return;
    setActionError("");
    setDeletingId(id);
    try {
      await transactionAPI.delete(id);
      setTransactions((prev) => prev.filter((t) => t.id !== id));
      setTotalCount((c) => Math.max(0, c - 1));
    } catch {
      setActionError("Failed to delete. Please try again.");
    } finally {
      setDeletingId(null);
    }
  };

  const handleAdded = (tx: Transaction) => {
    setTransactions((prev) => [tx, ...prev]);
    setTotalCount((c) => c + 1);
  };

  // Additional safety filtering on whatever current page items are loaded
  const filtered = transactions
    .filter((t) => filter === "ALL" || t.type === filter)
    .filter((t) =>
      categoryFilter === "ALL" ? true : categoryFilter === "UNCATEGORIZED" ? isUncategorised(t) : needsReview(t));

  // Transfers, card-bill payments, investment movements and refunds are not income or spending.
  const totalIncome = transactions
    .filter((t) => t.type === "INCOME" && countsTowardTotals(t.category))
    .reduce((s, t) => s + t.amount, 0);
  const totalExpense = transactions
    .filter((t) => t.type === "EXPENSE" && countsTowardTotals(t.category))
    .reduce((s, t) => s + t.amount, 0);

  const awaitingCategory = transactions.filter((t) => (t.categorySource ?? "NONE") === "NONE" && !t.category).length;
  const toReview = transactions.filter(needsReview).length;

  if (loading && transactions.length === 0) return <FullPageSpinner />;

  return (
    <div className="min-h-screen bg-[#080a0e] text-white font-['DM_Sans',sans-serif]">
      {showModal && (
        <AddTransactionModal onClose={() => setShowModal(false)} onAdded={handleAdded} />
      )}

      <PageNav accent="cyan" onAdd={() => setShowModal(true)} />

      <main className="max-w-5xl mx-auto px-6 py-10">
        <div className="mb-8 flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Transactions</h1>
            <p className="text-white/40 text-sm mt-1">{totalCount} total entries</p>
          </div>
          <div className="flex gap-3">
            <div
              className="px-4 py-2 rounded-xl bg-emerald-500/10 border border-emerald-500/20"
              title="This page. Excludes transfers, card payments, investments and refunds."
            >
              <p className="text-xs text-emerald-400/60 uppercase tracking-widest">Income · this page</p>
              <p className="text-sm font-semibold text-emerald-400">{formatCurrency(totalIncome)}</p>
            </div>
            <div
              className="px-4 py-2 rounded-xl bg-rose-500/10 border border-rose-500/20"
              title="This page. Excludes transfers, card payments, investments and refunds."
            >
              <p className="text-xs text-rose-400/60 uppercase tracking-widest">Expenses · this page</p>
              <p className="text-sm font-semibold text-rose-400">{formatCurrency(totalExpense)}</p>
            </div>
          </div>
        </div>

        <div className="flex gap-2 mb-6">
          {(["ALL", "INCOME", "EXPENSE"] as const).map((f) => (
            <button
              key={f}
              onClick={() => handleFilterChange(f)}
              className={`px-4 py-2 rounded-xl text-sm font-medium transition-all ${
                filter === f ? "bg-white/10 text-white" : "text-white/40 hover:text-white/70"
              }`}
            >
              {f.charAt(0) + f.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap gap-2 mb-6 -mt-3" role="group" aria-label="Category status">
          {CATEGORY_FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              aria-pressed={categoryFilter === f.id}
              onClick={() => handleCategoryFilterChange(f.id)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                categoryFilter === f.id ? "bg-white/10 text-white" : "text-white/40 hover:text-white/70"
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>

        {(awaitingCategory > 0 || toReview > 0) && (
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3 px-4 py-3 rounded-xl bg-violet-500/[0.07] border border-violet-500/20">
            <p className="text-xs text-white/60">
              {awaitingCategory > 0 && (
                <span>
                  {awaitingCategory} {awaitingCategory === 1 ? "transaction needs" : "transactions need"} a category on this page.{" "}
                </span>
              )}
              {toReview > 0 && (
                <span>{toReview} automatic {toReview === 1 ? "category is" : "categories are"} worth a quick check.</span>
              )}
            </p>
            {awaitingCategory > 0 && (
              <button
                type="button"
                onClick={handleReclassify}
                disabled={reclassifying}
                className="text-xs px-3 py-1.5 rounded-lg bg-violet-500/20 text-violet-200 hover:bg-violet-500/30 disabled:opacity-50 transition-colors"
              >
                {reclassifying ? "Categorizing…" : "Categorize automatically"}
              </button>
            )}
          </div>
        )}

        {notice && (
          <p role="status" className="mb-4 px-4 py-3 rounded-xl text-xs text-white/70 bg-white/[0.04] border border-white/[0.08]">
            {notice}
          </p>
        )}

        <ErrorBanner message={error || actionError} />

        <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] overflow-hidden">
          {filtered.length === 0 ? (
            <div className="text-center py-16 text-white/30">
              <p className="text-4xl mb-3">📭</p>
              <p className="text-sm mb-4">No transactions found.</p>
              <AddButton accent="cyan" onClick={() => setShowModal(true)}>
                + Add your first transaction
              </AddButton>
            </div>
          ) : (
            <>
              <table className="w-full">
                <thead>
                  <tr className="border-b border-white/[0.06]">
                    <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Title</th>
                    <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Category</th>
                    <th className="text-left px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Type</th>
                    <th className="text-right px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Amount</th>
                    <th className="text-right px-6 py-4 text-xs text-white/30 uppercase tracking-widest font-medium">Date</th>
                    <th className="px-6 py-4" />
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((tx) => (
                    <tr key={tx.id} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors">
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-2">
                          <p className="text-sm font-medium text-white/90">{tx.title}</p>
                          {tx.source === "IMPORT" && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-400 font-medium">
                              Import
                            </span>
                          )}
                        </div>
                        {tx.note && <p className="text-xs text-white/30 mt-0.5">{tx.note}</p>}
                      </td>
                      <td className="px-6 py-4">
                        <CategoryCell
                          tx={tx}
                          saving={savingId === tx.id}
                          onSelect={(category) => handleCategory(tx, category)}
                        />
                      </td>
                      <td className="px-6 py-4">
                        <span className={`text-xs font-semibold px-2 py-1 rounded-lg ${
                          tx.type === "INCOME" ? "bg-emerald-500/10 text-emerald-400" : "bg-rose-500/10 text-rose-400"
                        }`}>
                          {tx.type}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-right">
                        <span className={`text-sm font-semibold ${tx.type === "INCOME" ? "text-emerald-400" : "text-rose-400"}`}>
                          {tx.type === "INCOME" ? "+" : "-"}{formatCurrency(tx.amount)}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-right text-xs text-white/30">{formatDate(tx.date)}</td>
                      <td className="px-6 py-4 text-right">
                        <button
                          onClick={() => handleDelete(tx.id)}
                          disabled={deletingId === tx.id}
                          aria-label={`Delete ${tx.title}`}
                          className="text-white/20 hover:text-rose-400 transition-colors text-sm disabled:opacity-40"
                        >
                          {deletingId === tx.id ? "…" : "✕"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="flex items-center justify-between px-6 py-4 border-t border-white/[0.06] text-xs text-white/50">
                <span>Page {page + 1} of {Math.max(1, totalPages)} ({totalCount} entries)</span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                    aria-label="Previous page"
                    className="px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 disabled:opacity-30 disabled:pointer-events-none text-white transition-all"
                  >
                    Previous
                  </button>
                  <button
                    onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                    disabled={page >= totalPages - 1}
                    aria-label="Next page"
                    className="px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 disabled:opacity-30 disabled:pointer-events-none text-white transition-all"
                  >
                    Next
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
