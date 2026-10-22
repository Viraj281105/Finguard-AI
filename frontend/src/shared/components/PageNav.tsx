import { useNavigate } from "react-router-dom";
import { ACCENT_STYLES, type Accent } from "./accent";

/** Top bar of the list pages: back to the dashboard, brand, and the page's "+ Add" action. */
export default function PageNav({ accent, onAdd }: { accent: Accent; onAdd: () => void }) {
  const navigate = useNavigate();
  return (
    <nav className="sticky top-0 z-40 border-b border-white/[0.05] bg-[#080a0e]/80 backdrop-blur-xl px-6 py-4">
      <div className="max-w-5xl mx-auto flex items-center justify-between">
        <button
          onClick={() => navigate("/dashboard")}
          className="text-white/50 hover:text-white transition-colors text-sm"
        >
          ← Dashboard
        </button>
        <span className="font-bold text-lg tracking-tight">
          Fin<span className="text-[#00d4ff]">Guard</span>
        </span>
        <AddButton accent={accent} onClick={onAdd}>
          + Add
        </AddButton>
      </div>
    </nav>
  );
}

export function AddButton({
  accent,
  onClick,
  children,
}: {
  accent: Accent;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={`px-4 py-2 text-sm text-black font-semibold rounded-xl transition-colors ${ACCENT_STYLES[accent].solid}`}
    >
      {children}
    </button>
  );
}
