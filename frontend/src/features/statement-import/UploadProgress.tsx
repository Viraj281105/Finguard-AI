import { useState } from "react";
import type { UploadStatus } from "./types";
import { bankEmoji, statusColor } from "./uploadStyles";

/**
 * How automatic categorisation went. It never affects whether the import succeeded: when the AI
 * service is down the transactions are saved without categories and can be categorised later.
 */
function ClassificationNotice({ job }: { job: UploadStatus }) {
  const imported = job.rowsProcessed ?? 0;
  const classified = job.classifiedRows ?? 0;
  const status = job.classificationStatus;
  if (imported === 0 || !status || status === "NOT_ATTEMPTED") return null;

  if (status === "UNAVAILABLE") {
    return (
      <div role="status" className="flex items-start gap-2 p-3 rounded-xl bg-white/[0.04] border border-white/[0.08]">
        <span>🏷️</span>
        <p className="text-white/60 text-sm">
          Automatic categorization is unavailable right now. Your transactions were imported without categories.
          Choose categories yourself, or use &ldquo;Categorize automatically&rdquo; on the Transactions page later.
        </p>
      </div>
    );
  }

  const remaining = imported - classified;
  return (
    <div role="status" className="flex items-start gap-2 p-3 rounded-xl bg-violet-500/10 border border-violet-500/20">
      <span>🏷️</span>
      <p className="text-violet-200 text-sm">
        {classified} of {imported} {imported === 1 ? "transaction was" : "transactions were"} categorized automatically.
        {remaining > 0 && ` ${remaining} ${remaining === 1 ? "needs" : "need"} a category: review ${remaining === 1 ? "it" : "them"} on the Transactions page.`}
        {status === "PARTIAL" && " Categorization was only partly completed; you can run it again from the Transactions page."}
      </p>
    </div>
  );
}

/** Shows a running or finished import: file and bank, status badge, progress bar, counts, and row error reports. */
export default function UploadProgress({ job, isDone }: { job: UploadStatus; isDone: boolean }) {
  const [showErrors, setShowErrors] = useState(false);
  const color = statusColor(job.status);
  const hasErrors = (job.rejectedRows ?? 0) > 0 && Array.isArray(job.rowErrors) && job.rowErrors.length > 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 p-4 rounded-xl bg-white/[0.03] border border-white/[0.05]">
        <span className="text-2xl">{bankEmoji(job.bankName)}</span>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-white truncate">{job.originalFilename}</p>
          <p className="text-xs text-white/40 mt-0.5">{job.bankName} Bank detected</p>
        </div>
        <span
          className="text-xs font-semibold px-3 py-1 rounded-full"
          style={{ background: `${color}18`, color }}
        >
          {job.status}
        </span>
      </div>

      <div>
        <div className="flex justify-between text-xs text-white/40 mb-2">
          <span>
            {isDone ? `${job.rowsProcessed} transactions imported` : "Processing transactions..."}
          </span>
          <span>
            {job.totalRows > 0 ? `${job.rowsProcessed} / ${job.totalRows}` : "Detecting rows..."}
          </span>
        </div>
        <div className="h-1.5 rounded-full bg-white/[0.06] overflow-hidden">
          <div
            className="h-full rounded-full transition-all duration-500"
            style={{
              width: isDone
                ? "100%"
                : job.totalRows > 0
                  ? `${Math.min(100, Math.max(5, Math.round(((job.rowsProcessed + (job.duplicateRows ?? 0) + (job.rejectedRows ?? 0) + (job.skippedRows ?? 0)) / job.totalRows) * 100)))}%`
                  : "30%",
              background: color,
            }}
          />
        </div>
      </div>

      {isDone && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20">
            <span>{job.rowsProcessed > 0 ? "✅" : "ℹ️"}</span>
            <p className="text-emerald-400 text-sm font-medium">
              {job.rowsProcessed > 0
                ? `${job.rowsProcessed} transactions imported successfully! Your dashboard will update now.`
                : (job.duplicateRows ?? 0) > 0
                  ? "All transactions in this statement were already imported."
                  : "No transactions found in this statement (header-only or metadata only)."}
            </p>
          </div>

          <ClassificationNotice job={job} />

          {(job.duplicateRows ?? 0) > 0 && (
            <div className="flex items-center gap-2 p-3 rounded-xl bg-amber-500/10 border border-amber-500/20">
              <span>ℹ️</span>
              <p className="text-amber-400 text-sm font-medium">
                {job.duplicateRows} duplicate {job.duplicateRows === 1 ? "entry was" : "entries were"} detected and safely skipped.
              </p>
            </div>
          )}

          {(job.skippedRows ?? 0) > 0 && (
            <div className="flex items-center gap-2 p-3 rounded-xl bg-blue-500/10 border border-blue-500/20">
              <span>📋</span>
              <p className="text-blue-400 text-sm font-medium">
                {job.skippedRows} non-financial {job.skippedRows === 1 ? "row was" : "rows were"} skipped (zero amount or blank).
              </p>
            </div>
          )}

          {(job.rejectedRows ?? 0) > 0 && (
            <div className="space-y-2 p-3 rounded-xl bg-rose-500/10 border border-rose-500/20">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span>⚠️</span>
                  <p className="text-rose-400 text-sm font-medium">
                    {job.rejectedRows} {job.rejectedRows === 1 ? "row was" : "rows were"} rejected due to format errors.
                  </p>
                </div>
                {hasErrors && (
                  <button
                    type="button"
                    onClick={() => setShowErrors(!showErrors)}
                    className="text-xs text-rose-300 hover:text-rose-200 underline"
                  >
                    {showErrors ? "Hide details" : "View errors"}
                  </button>
                )}
              </div>

              {showErrors && hasErrors && (
                <div className="mt-2 pt-2 border-t border-rose-500/20 space-y-1.5 max-h-40 overflow-y-auto text-xs">
                  {job.rowErrors!.map((err, idx) => (
                    <div key={idx} className="text-rose-300 font-mono">
                      <span className="font-semibold text-rose-200">Line {err.rowNumber}:</span> {err.errorMessage}
                      {err.snippet && (
                        <div className="text-white/40 truncate text-[11px] ml-2">"{err.snippet}"</div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {(job.closingBalance != null || job.statementFrom != null) && (
            <div className="flex items-center justify-between px-3 py-2 text-xs text-white/50 bg-white/[0.02] rounded-lg">
              {job.statementFrom && job.statementTo && (
                <span>Period: {job.statementFrom} to {job.statementTo}</span>
              )}
              {job.closingBalance != null && (
                <span>Closing Balance: ₹{Number(job.closingBalance).toLocaleString("en-IN", { minimumFractionDigits: 2 })}</span>
              )}
            </div>
          )}
        </div>
      )}

      {!isDone && (
        <div className="flex items-center gap-2">
          <div className="w-3 h-3 rounded-full border border-[#00d4ff]/40 border-t-[#00d4ff] animate-spin" />
          <p className="text-xs text-white/30">Checking status...</p>
        </div>
      )}
    </div>
  );
}
