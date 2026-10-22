const inr = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 0,
});

/** Formats an amount as whole rupees, e.g. 1234567 -> "₹12,34,567". */
export const formatCurrency = (amount: number): string => inr.format(amount);

/** Formats an ISO date or timestamp as "05 Mar 2026"; an empty or invalid value gives "—". */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const dateStr = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? `${iso}T00:00:00` : iso;
  const date = new Date(dateStr);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleDateString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}
