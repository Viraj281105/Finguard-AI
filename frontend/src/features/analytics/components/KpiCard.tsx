
interface KpiCardProps {
  label: string;
  value: string;
  sub?: string;
  changePct?: number | null;
  changeLabel?: string;
  isInverseTrend?: boolean; // For expenses: positive change is bad (red), negative is good (green)
  caveat?: string | null;
  badge?: string;
}

export default function KpiCard({
  label,
  value,
  sub,
  changePct,
  changeLabel = "vs prior period",
  isInverseTrend = false,
  caveat,
  badge,
}: KpiCardProps) {
  let changeColor = "text-slate-500 bg-slate-100";
  let changeArrow = "";

  if (changePct !== undefined && changePct !== null) {
    const isPositive = changePct > 0;
    const isGood = isInverseTrend ? !isPositive : isPositive;

    changeColor = isGood
      ? "text-emerald-700 bg-emerald-50 border border-emerald-200/60"
      : "text-rose-700 bg-rose-50 border border-rose-200/60";

    changeArrow = isPositive ? "↑ " : "↓ ";
  }

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm hover:shadow transition-shadow">
      <div className="flex items-center justify-between gap-2 mb-2">
        <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">{label}</span>
        {badge && (
          <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-sky-50 text-sky-700 border border-sky-100">
            {badge}
          </span>
        )}
      </div>

      <div className="flex items-baseline gap-2 mb-1">
        <span className="text-2xl font-bold tracking-tight text-slate-900">{value}</span>
      </div>

      <div className="flex flex-wrap items-center gap-2 pt-1 text-xs">
        {changePct !== undefined && changePct !== null && (
          <span className={`inline-flex items-center px-1.5 py-0.5 rounded font-medium ${changeColor}`}>
            {changeArrow}
            {Math.abs(changePct).toFixed(1)}%
          </span>
        )}
        {sub && <span className="text-slate-500">{sub}</span>}
        {changePct !== undefined && changePct !== null && (
          <span className="text-slate-400 text-[11px]">{changeLabel}</span>
        )}
      </div>

      {caveat && (
        <div className="mt-2.5 pt-2 border-t border-slate-100 text-[11px] text-amber-700 flex items-start gap-1">
          <span>ℹ</span>
          <span>{caveat}</span>
        </div>
      )}
    </div>
  );
}
