import { useState } from "react";
import { categoriesForType, categoryInfo, categoryLabel } from "../../shared/categories";
import type { Transaction } from "./types";

interface Props {
  tx: Transaction;
  /** Persist a category chosen or confirmed by the user. Rejects on failure. */
  onSelect: (category: string) => Promise<void>;
  saving: boolean;
}

/**
 * The category of one transaction, with how it was decided:
 *  - confirmed by the user (check mark; never changed by automation),
 *  - suggested automatically ("Auto", plus "Review" when the evidence is weak or conflicting),
 *  - or missing ("Needs a category").
 * A confidence percentage is shown only when the server says it is a calibrated probability; the
 * current rule-based classifier's score is a strength tier, so none is shown for it.
 * Clicking the badge opens an inline picker limited to categories that suit the direction.
 */
export default function CategoryCell({ tx, onSelect, saving }: Props) {
  const [editing, setEditing] = useState(false);
  const source = tx.categorySource ?? (tx.category ? "USER" : "NONE");
  const automatic = source === "RULE" || source === "MODEL";
  const hasCategory = Boolean(tx.category);
  const label = categoryLabel(tx.category);

  const options = categoriesForType(tx.type);
  const currentMissingFromOptions = tx.category && !options.some((c) => c.id === tx.category);

  const choose = async (category: string) => {
    setEditing(false);
    if (category) await onSelect(category).catch(() => undefined);
  };

  if (editing) {
    return (
      <select
        autoFocus
        aria-label={`Category for ${tx.title}`}
        defaultValue={tx.category ?? ""}
        disabled={saving}
        onChange={(e) => void choose(e.target.value)}
        onBlur={() => setEditing(false)}
        onKeyDown={(e) => {
          if (e.key === "Escape") setEditing(false);
        }}
        className="text-xs px-2 py-1 rounded-lg bg-[#0f1117] border border-white/[0.15] text-white/80 focus:outline-none focus:border-[#00d4ff]/40 max-w-[11rem]"
      >
        <option value="" disabled>
          Choose a category…
        </option>
        {currentMissingFromOptions && (
          <option value={tx.category ?? ""}>{label}</option>
        )}
        {options.map((c) => (
          <option key={c.id} value={c.id}>
            {c.label}
          </option>
        ))}
      </select>
    );
  }

  const nonSpending = categoryInfo(tx.category)?.flow === "NON_SPENDING" || categoryInfo(tx.category)?.flow === "REFUND";
  const showConfidence =
    automatic && tx.categoryConfidenceCalibrated === true && typeof tx.categoryConfidence === "number";
  const basis = [tx.categoryRule && `rule ${tx.categoryRule}`, tx.categoryModelVersion].filter(Boolean).join(", ");
  const provenance = automatic
    ? `Suggested automatically${basis ? ` (${basis})` : ""}. Choose a category to confirm or change it.`
    : source === "USER"
      ? "You chose this category. Automatic categorization will not change it."
      : "No category yet. Choose one, or use Categorize automatically.";

  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      <button
        type="button"
        onClick={() => setEditing(true)}
        disabled={saving}
        aria-label={`Change category for ${tx.title}`}
        title={provenance}
        className={`text-xs px-2 py-1 rounded-lg transition-colors disabled:opacity-50 ${
          hasCategory
            ? `bg-white/5 text-white/60 hover:bg-white/10 ${nonSpending ? "italic" : ""}`
            : "border border-dashed border-white/20 text-white/40 hover:border-white/40 hover:text-white/60"
        }`}
      >
        {label}
        {source === "USER" && hasCategory && (
          <span aria-hidden="true" className="ml-1 text-emerald-400">✓</span>
        )}
      </button>

      {source === "USER" && hasCategory && <span className="sr-only">Confirmed by you</span>}

      {automatic && (
        <>
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-violet-500/10 text-violet-300 font-medium">
            Auto
          </span>
          {tx.categoryNeedsReview && (
            <span
              className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-300 font-medium"
              title="Weak or conflicting evidence: please check this category"
            >
              Review
            </span>
          )}
          {showConfidence && (
            <span className="text-[10px] text-white/40">{Math.round((tx.categoryConfidence ?? 0) * 100)}%</span>
          )}
          <button
            type="button"
            disabled={saving}
            onClick={() => void choose(tx.category ?? "")}
            aria-label={`Confirm category for ${tx.title}`}
            className="text-[10px] px-1.5 py-0.5 rounded text-white/40 hover:text-emerald-300 hover:bg-emerald-500/10 transition-colors disabled:opacity-40"
          >
            Confirm
          </button>
        </>
      )}
    </div>
  );
}
