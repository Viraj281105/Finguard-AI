import React, { useState } from "react";

export type PresetKey = "ALL" | "THIS_MONTH" | "LAST_3_MONTHS" | "LAST_6_MONTHS" | "YTD" | "CUSTOM";

interface DateRangeSelectorProps {
  onRangeChange: (from?: string, to?: string) => void;
}

export default function DateRangeSelector({ onRangeChange }: DateRangeSelectorProps) {
  const [activePreset, setActivePreset] = useState<PresetKey>("ALL");
  const [customFrom, setCustomFrom] = useState<string>("");
  const [customTo, setCustomTo] = useState<string>("");

  const applyPreset = (preset: PresetKey) => {
    setActivePreset(preset);
    const today = new Date();
    const pad = (n: number) => n.toString().padStart(2, "0");
    const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

    if (preset === "ALL") {
      onRangeChange(undefined, undefined);
    } else if (preset === "THIS_MONTH") {
      const from = new Date(today.getFullYear(), today.getMonth(), 1);
      onRangeChange(fmt(from), fmt(today));
    } else if (preset === "LAST_3_MONTHS") {
      const from = new Date(today.getFullYear(), today.getMonth() - 2, 1);
      onRangeChange(fmt(from), fmt(today));
    } else if (preset === "LAST_6_MONTHS") {
      const from = new Date(today.getFullYear(), today.getMonth() - 5, 1);
      onRangeChange(fmt(from), fmt(today));
    } else if (preset === "YTD") {
      const from = new Date(today.getFullYear(), 0, 1);
      onRangeChange(fmt(from), fmt(today));
    }
  };

  const handleCustomApply = (e: React.FormEvent) => {
    e.preventDefault();
    if (customFrom && customTo) {
      setActivePreset("CUSTOM");
      onRangeChange(customFrom, customTo);
    }
  };

  const presets: { id: PresetKey; label: string }[] = [
    { id: "ALL", label: "All Time" },
    { id: "THIS_MONTH", label: "This Month" },
    { id: "LAST_3_MONTHS", label: "Last 3 Months" },
    { id: "LAST_6_MONTHS", label: "Last 6 Months" },
    { id: "YTD", label: "Year to Date" },
  ];

  return (
    <div className="flex flex-wrap items-center justify-between gap-4 p-3 bg-white rounded-2xl border border-slate-200/80 shadow-xs mb-8">
      {/* Preset pills */}
      <div className="flex flex-wrap items-center gap-1.5">
        {presets.map((p) => (
          <button
            key={p.id}
            type="button"
            onClick={() => applyPreset(p.id)}
            className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
              activePreset === p.id
                ? "bg-slate-900 text-white shadow-xs"
                : "bg-slate-50 text-slate-600 hover:bg-slate-100 hover:text-slate-900 border border-slate-200/60"
            }`}
          >
            {p.label}
          </button>
        ))}
      </div>

      {/* Custom Date Range */}
      <form onSubmit={handleCustomApply} className="flex items-center gap-2 text-xs">
        <span className="text-slate-400 font-medium">Custom:</span>
        <input
          type="date"
          value={customFrom}
          onChange={(e) => setCustomFrom(e.target.value)}
          className="px-2.5 py-1 rounded-lg border border-slate-200 bg-slate-50 text-slate-800 text-xs focus:outline-hidden focus:ring-1 focus:ring-sky-500"
          aria-label="Start date"
        />
        <span className="text-slate-400">–</span>
        <input
          type="date"
          value={customTo}
          onChange={(e) => setCustomTo(e.target.value)}
          className="px-2.5 py-1 rounded-lg border border-slate-200 bg-slate-50 text-slate-800 text-xs focus:outline-hidden focus:ring-1 focus:ring-sky-500"
          aria-label="End date"
        />
        <button
          type="submit"
          disabled={!customFrom || !customTo}
          className="px-3 py-1 rounded-lg bg-sky-600 text-white text-xs font-semibold hover:bg-sky-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Apply
        </button>
      </form>
    </div>
  );
}
