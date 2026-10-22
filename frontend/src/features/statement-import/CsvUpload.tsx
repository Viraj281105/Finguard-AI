import { useCallback, useEffect, useRef, useState } from "react";
import { apiErrorMessage } from "../../shared/api/client";
import { uploadAPI } from "./api";
import type { UploadStatus } from "./types";
import { MAX_FILE_BYTES, POLL_INTERVAL_MS, REFRESH_DELAY_MS } from "./uploadConfig";
import UploadProgress from "./UploadProgress";

type UploadState =
  | { phase: "idle" }
  | { phase: "uploading" }
  | { phase: "polling"; job: UploadStatus }
  | { phase: "done"; job: UploadStatus }
  | { phase: "error"; message: string };

/**
 * Drag-and-drop bank statement upload with live status.
 *
 * Flow: pick a CSV -> POST it (the server answers immediately with a job id) -> poll the job every
 * 2 s -> show PROCESSING, then COMPLETED (row count) or FAILED (reason) -> call `onUploadComplete`
 * so the parent can refresh its data.
 */
export default function CsvUpload({ onUploadComplete }: { onUploadComplete: () => void }) {
  const [state, setState] = useState<UploadState>({ phase: "idle" });
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onCompleteRef = useRef(onUploadComplete);

  useEffect(() => {
    onCompleteRef.current = onUploadComplete;
  }, [onUploadComplete]);

  const stopTimers = useCallback(() => {
    if (pollRef.current) clearInterval(pollRef.current);
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    pollRef.current = null;
    refreshTimerRef.current = null;
  }, []);

  // Never leave a timer running after the component unmounts.
  useEffect(() => stopTimers, [stopTimers]);

  const startPolling = useCallback(
    (jobId: string) => {
      pollRef.current = setInterval(async () => {
        try {
          const job = await uploadAPI.getStatus(jobId);

          if (job.status === "COMPLETED") {
            stopTimers();
            setState({ phase: "done", job });
            refreshTimerRef.current = setTimeout(() => onCompleteRef.current(), REFRESH_DELAY_MS);
          } else if (job.status === "FAILED") {
            stopTimers();
            setState({
              phase: "error",
              message: job.errorMessage ?? "Upload failed. Please try again.",
            });
          } else {
            setState({ phase: "polling", job });
          }
        } catch {
          stopTimers();
          setState({ phase: "error", message: "Could not check upload status." });
        }
      }, POLL_INTERVAL_MS);
    },
    [stopTimers],
  );

  const handleFile = useCallback(
    async (file: File) => {
      if (!file.name.toLowerCase().endsWith(".csv")) {
        setState({ phase: "error", message: "Please upload a CSV file." });
        return;
      }
      if (file.size > MAX_FILE_BYTES) {
        setState({ phase: "error", message: "File too large. Max 5MB." });
        return;
      }

      try {
        setState({ phase: "uploading" });
        const job = await uploadAPI.uploadCsv(file);
        setState({ phase: "polling", job });
        startPolling(job.jobId);
      } catch (err) {
        setState({
          phase: "error",
          message: apiErrorMessage(err, "Upload failed. Make sure the backend is running."),
        });
      }
    },
    [startPolling],
  );

  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) void handleFile(file);
  };

  const onFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) void handleFile(file);
  };

  const reset = () => {
    stopTimers();
    setState({ phase: "idle" });
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  return (
    <div className="rounded-2xl bg-[#0f1117] border border-white/[0.06] p-6">
      <div className="flex items-center justify-between mb-5">
        <div>
          <h2 className="text-lg font-semibold text-white">Import Bank Statement</h2>
          <p className="text-xs text-white/30 mt-0.5">Supports HDFC · ICICI · SBI · Axis · Kotak</p>
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

      {state.phase === "idle" && (
        <div
          onDragOver={onDragOver}
          onDragLeave={() => setIsDragging(false)}
          onDrop={onDrop}
          onClick={() => fileInputRef.current?.click()}
          className={`
            relative border-2 border-dashed rounded-xl p-10 text-center cursor-pointer
            transition-all duration-200
            ${
              isDragging
                ? "border-[#00d4ff] bg-[#00d4ff]/5"
                : "border-white/[0.08] hover:border-white/20 hover:bg-white/[0.02]"
            }
          `}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            aria-label="Bank statement CSV"
            onChange={onFileSelect}
            className="hidden"
          />
          <div className="flex flex-col items-center gap-3">
            <span className="text-4xl">{isDragging ? "📂" : "📁"}</span>
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

      {state.phase === "uploading" && (
        <div className="flex flex-col items-center gap-4 py-8" role="status">
          <div className="w-10 h-10 rounded-full border-2 border-[#00d4ff]/30 border-t-[#00d4ff] animate-spin" />
          <p className="text-white/50 text-sm">Uploading file...</p>
        </div>
      )}

      {(state.phase === "polling" || state.phase === "done") && (
        <UploadProgress job={state.job} isDone={state.phase === "done"} />
      )}

      {state.phase === "error" && (
        <div className="space-y-4">
          <div role="alert" className="flex items-start gap-3 p-4 rounded-xl bg-rose-500/10 border border-rose-500/20">
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
