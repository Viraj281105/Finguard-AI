interface MonthSelectorProps {
  currentMonth: string; // YYYY-MM
  onMonthChange: (month: string) => void;
}

export default function MonthSelector({ currentMonth, onMonthChange }: MonthSelectorProps) {
  const [yearStr, monthStr] = currentMonth.split("-");
  const year = parseInt(yearStr, 10);
  const month = parseInt(monthStr, 10);

  const formatMonthName = (y: number, m: number) => {
    const d = new Date(y, m - 1, 1);
    return d.toLocaleString("default", { month: "long", year: "numeric" });
  };

  const handlePrev = () => {
    let newYear = year;
    let newMonth = month - 1;
    if (newMonth < 1) {
      newMonth = 12;
      newYear -= 1;
    }
    const formatted = `${newYear}-${String(newMonth).padStart(2, "0")}`;
    onMonthChange(formatted);
  };

  const handleNext = () => {
    let newYear = year;
    let newMonth = month + 1;
    if (newMonth > 12) {
      newMonth = 1;
      newYear += 1;
    }
    const formatted = `${newYear}-${String(newMonth).padStart(2, "0")}`;
    onMonthChange(formatted);
  };

  const handleCurrent = () => {
    const now = new Date();
    const formatted = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
    onMonthChange(formatted);
  };

  const isCurrentMonth = () => {
    const now = new Date();
    const formatted = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
    return currentMonth === formatted;
  };

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 bg-white p-3.5 rounded-2xl border border-slate-200/80 shadow-2xs">
      <div className="flex items-center gap-2">
        <button
          onClick={handlePrev}
          aria-label="Previous month"
          className="p-1.5 text-slate-500 hover:text-slate-900 hover:bg-slate-100 rounded-lg transition-colors border border-slate-200"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>

        <span className="text-sm font-bold text-slate-900 min-w-[140px] text-center">
          {formatMonthName(year, month)}
        </span>

        <button
          onClick={handleNext}
          aria-label="Next month"
          className="p-1.5 text-slate-500 hover:text-slate-900 hover:bg-slate-100 rounded-lg transition-colors border border-slate-200"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
        </button>
      </div>

      <div className="flex items-center gap-2">
        {!isCurrentMonth() && (
          <button
            onClick={handleCurrent}
            className="px-3 py-1 text-xs font-semibold text-sky-700 bg-sky-50 hover:bg-sky-100 rounded-lg transition-colors border border-sky-200/60"
          >
            Jump to Current Month
          </button>
        )}
        <span className="text-xs text-slate-400 font-mono">
          {currentMonth}
        </span>
      </div>
    </div>
  );
}
