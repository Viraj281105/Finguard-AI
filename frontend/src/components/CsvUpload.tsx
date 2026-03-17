import { useCallback, useRef, useState } from "react";
import { uploadAPI, type JobStatus, type UploadStatusResponse } from "../services/api";

// ─── Types ────────────────────────────────────────────────────────────────────

type UploadState =
  | { phase: "idle" }
  | { phase: "uploading" }
  | { phase: "polling"; job: UploadStatusResponse }
  | { phase: "done"; job: UploadStatusResponse }
  | { phase: "error"; message: string };

// ─── Helpers ──────────────────────────────────────────────────────────────────

/*
 * Status badge color based on job status
 */
function statusColor(status: JobStatus) {
  switch (status) {
    case "PENDING":     return "#fbbf24"; // amber
    case "PROCESSING":  return "#00d4ff"; // cyan
    case "COMPLETED":   return "#34d399"; // green
    case "FAILED":      return "#fb7185"; // red
  }
}

/*
 * Bank name to emoji mapping — makes the UI feel more Indian and friendly
 */
function bankEmoji(bank: string) {
  switch (bank?.toUpperCase()) {
    case "HDFC":  return "🏦";
    case "ICICI": return "🏛️";
    case "SBI":   return "🏢";
    case "AXIS":  return "🏗️";
    case "KOTAK": return "🏠";
    default:      return "📄";
  }
}

// ─── Main Component ───────────────────────────────────────────────────────────

/*
 * CsvUpload
 * ---------
 * Drag-and-drop CSV upload component with real-time status polling.
 *
 * Flow:
 *   1. User drags or selects a CSV file
 *   2. File is uploaded → backend returns jobId immediately (PENDING)
 *   3. Component polls /upload/status/{jobId} every 2 seconds
 *   4. Shows PROCESSING → COMPLETED (with row count) or FAILED (with error)
 *   5. On success, calls onUploadComplete() so parent can refresh data
 *
 * Props:
 *   onUploadComplete → called when CSV is fully processed
 *                      parent should re-fetch transactions after this
 */
export default function CsvUpload({
  onUploadComplete,
}: {
  onUploadComplete: () => void;
}) {
  const [state, setState] = useState<UploadState>({ phase: "idle" });
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // ── Polling ────────────────────────────────────────────────────────────────

  /*
   * startPolling()
   * --------------
   * Calls GET /upload/status/{jobId} every 2 seconds.
   * Stops when status = COMPLETED or FAILED.
   */
  const startPolling = useCallback((jobId: string) => {
    pollIntervalRef.current = setInterval(async () => {
      try {
        const job = await uploadAPI.getStatus(jobId);
        setState({ phase: "polling", job });

        if (job.status === "COMPLETED") {
            clearInterval(pollIntervalRef.current!);
            setState({ phase: "done", job });
  // Wait 3 seconds so user can see the success message
  // before the dashboard refreshes
            setTimeout(() => {
            onUploadComplete();
              }, 3000); // tell parent to refresh transactions
        } else if (job.status === "FAILED") {
          clearInterval(pollIntervalRef.current!);
          setState({
            phase: "error",
            message: job.errorMessage ?? "Upload failed. Please try again.",
          });
        }
      } catch {
        clearInterval(pollIntervalRef.current!);
        setState({ phase: "error", message: "Could not check upload status." });
      }
    }, 2000); // poll every 2 seconds
  }, [onUploadComplete]);

  // ── File handling ──────────────────────────────────────────────────────────

  const handleFile = useCallback(async (file: File) => {
    // Validate file type
    if (!file.name.toLowerCase().endsWith(".csv")) {
      setState({ phase: "error", message: "Please upload a CSV file." });
      return;
    }

    // Validate file size (max 5MB)
    if (file.size > 5 * 1024 * 1024) {
      setState({ phase: "error", message: "File too large. Max 5MB." });
      return;
    }

    try {
      setState({ phase: "uploading" });
      const job = await uploadAPI.uploadCsv(file);
      setState({ phase: "polling", job });
      startPolling(job.jobId);
    } catch {
      setState({
        phase: "error",
        message: "Upload failed. Make sure the backend is running.",
      });
    }
  }, [startPolling]);

  // ── Drag & Drop handlers ───────────────────────────────────────────────────

  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const onDragLeave = () => setIsDragging(false);

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  };

  const onFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) handleFile(file);
  };

  const reset = () => {
    if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    setState({ phase: "idle" });
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <div>
          <h2 className="text-lg font-semibold text-white">Import Bank Statement</h2>
          <p className="text-xs text-white/30 mt-0.5">
            Supports HDFC · ICICI · SBI · Axis · Kotak
          </p>
        </div>
        {state.phase !== "idle" && (
          <button
            onClick={reset}
            className="text-xs text-white/40 hover:text-white/70 transition-colors px-3 py-1.5 rounded-lg hover:bg-white/5"
          >
            Upload another
          </button>
        )}
      </div>

      {/* ── IDLE: Drop zone ── */}
      {state.phase === "idle" && (
        <div
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onDrop={onDrop}
          onClick={() => fileInputRef.current?.click()}
          className={`
            relative border-2 border-dashed rounded-xl p-10 text-center cursor-pointer
            transition-all duration-200
            ${isDragging
              ? "border-[#00d4ff] bg-[#00d4ff]/5"
              : "border-white/[0.08] hover:border-white/20 hover:bg-white/[0.02]"
            }
          `}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            onChange={onFileSelect}
            className="hidden"
          />
          <div className="flex flex-col items-center gap-3">
            <span className="text-4xl">
              {isDragging ? "📂" : "📁"}
            </span>
            <div>
              <p className="text-white/70 font-medium">
                {isDragging ? "Drop your CSV here" : "Drag & drop your bank statement"}
              </p>
              <p className="text-white/30 text-sm mt-1">
                or <span className="text-[#00d4ff]">browse files</span> · CSV only · Max 5MB
              </p>
            </div>
          </div>
        </div>
      )}

      {/* ── UPLOADING: Spinner ── */}
      {state.phase === "uploading" && (
        <div className="flex flex-col items-center gap-4 py-8">
          <div className="w-10 h-10 rounded-full border-2 border-[#00d4ff]/30 border-t-[#00d4ff] animate-spin" />
          <p className="text-white/50 text-sm">Uploading file...</p>
        </div>
      )}

      {/* ── POLLING: Processing status ── */}
      {(state.phase === "polling" || state.phase === "done") && (
        (() => {
          const job = state.phase === "polling" ? state.job : (state as { phase: "done"; job: UploadStatusResponse }).job;
          const isDone = state.phase === "done";
          const color = statusColor(job.status);

          return (
            <div className="space-y-4">
              {/* Bank + filename info */}
              <div className="flex items-center gap-3 p-4 rounded-xl bg-white/[0.03] border border-white/[0.05]">
                <span className="text-2xl">{bankEmoji(job.bankName)}</span>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-white truncate">
                    {job.originalFilename}
                  </p>
                  <p className="text-xs text-white/40 mt-0.5">
                    {job.bankName} Bank detected
                  </p>
                </div>
                {/* Status badge */}
                <span
                  className="text-xs font-semibold px-3 py-1 rounded-full"
                  style={{ background: `${color}18`, color }}
                >
                  {job.status}
                </span>
              </div>

              {/* Progress bar */}
              <div>
                <div className="flex justify-between text-xs text-white/40 mb-2">
                  <span>
                    {isDone
                      ? `${job.rowsProcessed} transactions imported`
                      : "Processing transactions..."}
                  </span>
                  <span>
                    {job.totalRows > 0
                      ? `${job.rowsProcessed} / ${job.totalRows}`
                      : "Detecting rows..."}
                  </span>
                </div>
                <div className="h-1.5 rounded-full bg-white/[0.06] overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: job.totalRows > 0
                        ? `${(job.rowsProcessed / job.totalRows) * 100}%`
                        : isDone ? "100%" : "30%",
                      background: color,
                    }}
                  />
                </div>
              </div>

              {/* Success message */}
              {isDone && (
                <div className="flex items-center gap-2 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20">
                  <span>✅</span>
                  <p className="text-emerald-400 text-sm font-medium">
                    {job.rowsProcessed} transactions imported successfully!
                    Your dashboard will update now.
                  </p>
                </div>
              )}

              {/* Polling spinner */}
              {!isDone && (
                <div className="flex items-center gap-2">
                  <div className="w-3 h-3 rounded-full border border-[#00d4ff]/40 border-t-[#00d4ff] animate-spin" />
                  <p className="text-xs text-white/30">Checking status...</p>
                </div>
              )}
            </div>
          );
        })()
      )}

      {/* ── ERROR state ── */}
      {state.phase === "error" && (
        <div className="space-y-4">
          <div className="flex items-start gap-3 p-4 rounded-xl bg-rose-500/10 border border-rose-500/20">
            <span className="text-lg">❌</span>
            <div>
              <p className="text-rose-400 text-sm font-medium">Upload failed</p>
              <p className="text-rose-400/70 text-xs mt-1">{state.message}</p>
            </div>
          </div>
          <button
            onClick={reset}
            className="w-full py-2.5 rounded-xl text-sm font-medium bg-white/5 hover:bg-white/10 text-white/70 hover:text-white transition-all"
          >
            Try again
          </button>
        </div>
      )}
    </div>
  );
}