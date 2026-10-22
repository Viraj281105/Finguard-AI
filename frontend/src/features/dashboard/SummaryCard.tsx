export default function SummaryCard({
  label,
  value,
  sub,
  accent,
  icon,
}: {
  label: string;
  value: string;
  sub?: string;
  accent: string;
  icon: string;
}) {
  return (
    <div
      className="relative overflow-hidden rounded-2xl bg-[#0f1117] border border-white/[0.06] p-6
                 hover:border-white/[0.14] transition-all duration-300 group"
    >
      <div
        className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-500"
        style={{
          background: `radial-gradient(ellipse at top left, ${accent}18 0%, transparent 60%)`,
        }}
      />
      <div className="relative z-10">
        <div className="flex items-start justify-between mb-4">
          <span className="text-2xl p-2 rounded-xl" style={{ background: `${accent}18` }}>
            {icon}
          </span>
          <span
            className="text-xs font-semibold tracking-widest uppercase px-2 py-1 rounded-full"
            style={{ background: `${accent}18`, color: accent }}
          >
            {label}
          </span>
        </div>
        <p className="text-3xl font-bold text-white tracking-tight">{value}</p>
        {sub && (
          <p className="text-sm mt-1" style={{ color: `${accent}cc` }}>
            {sub}
          </p>
        )}
      </div>
    </div>
  );
}
