import { ACCENT_STYLES, type Accent } from "./accent";

/** Full-screen loading indicator used while a page's data loads. */
export default function FullPageSpinner({
  accent = "cyan",
  label,
}: {
  accent?: Accent;
  label?: string;
}) {
  return (
    <div className="min-h-screen bg-[#080a0e] flex items-center justify-center" role="status">
      <div className="flex flex-col items-center gap-4">
        <div className={`w-10 h-10 rounded-full border-2 animate-spin ${ACCENT_STYLES[accent].spinner}`} />
        {label && <p className="text-white/40 text-sm tracking-widest uppercase">{label}</p>}
      </div>
    </div>
  );
}
