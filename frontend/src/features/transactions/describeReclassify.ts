import type { ReclassifyResult } from "./types";

/** Plain-language summary of a "Categorize automatically" run, including the failure states. */
export function describeReclassify(r: ReclassifyResult): string {
  if (r.status === "UNAVAILABLE") {
    return "Automatic categorization is unavailable right now. Your transactions are safe and unchanged. You can choose categories yourself or try again later.";
  }
  if (r.status === "NOT_ATTEMPTED" || r.attempted === 0) {
    return "There was nothing to categorize.";
  }
  const parts = [`Categorized ${r.categorized} of ${r.attempted} transactions.`];
  if (r.status === "PARTIAL") parts.push("Some requests could not be completed; try again to finish.");
  if (r.stillUncategorized > 0) {
    parts.push(
      `${r.stillUncategorized} still ${r.stillUncategorized === 1 ? "needs" : "need"} a category: pick one yourself.`,
    );
  }
  return parts.join(" ");
}
