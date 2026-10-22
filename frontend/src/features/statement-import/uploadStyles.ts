import type { JobStatus } from "./types";

export function statusColor(status: JobStatus): string {
  switch (status) {
    case "PENDING":
      return "#fbbf24"; // amber
    case "PROCESSING":
      return "#00d4ff"; // cyan
    case "COMPLETED":
      return "#34d399"; // green
    case "FAILED":
      return "#fb7185"; // red
  }
}

export function bankEmoji(bank: string): string {
  switch (bank?.toUpperCase()) {
    case "HDFC":
      return "🏦";
    case "ICICI":
      return "🏛️";
    case "SBI":
      return "🏢";
    case "AXIS":
      return "🏗️";
    case "KOTAK":
      return "🏠";
    default:
      return "📄";
  }
}
