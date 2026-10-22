import { useId } from "react";
import { ACCENT_STYLES, type Accent } from "./accent";

/** Dimmed overlay with a centred card, a title and a close button. */
export function ModalShell({
  title,
  onClose,
  scrollable = false,
  children,
}: {
  title: string;
  onClose: () => void;
  scrollable?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      role="dialog"
      aria-label={title}
      className={`fixed inset-0 z-50 flex items-center justify-center px-4 ${
        scrollable ? "py-8 overflow-y-auto" : ""
      }`}
      style={{ background: "rgba(0,0,0,0.7)", backdropFilter: "blur(4px)" }}
    >
      <div
        className={`w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl p-6 shadow-2xl ${
          scrollable ? "my-auto" : ""
        }`}
      >
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-semibold text-white">{title}</h2>
          <button
            onClick={onClose}
            aria-label="Close"
            className="text-white/30 hover:text-white transition-colors text-xl leading-none"
          >
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

/** A labelled form row. `children` receives the id to put on the control so the label is associated with it. */
export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: (id: string) => React.ReactNode;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="block text-xs text-white/40 mb-1.5 uppercase tracking-widest">
        {label}
        {hint && <span className="text-white/20 normal-case"> — {hint}</span>}
      </label>
      {children(id)}
    </div>
  );
}

/** Cancel / submit buttons at the bottom of an add-form. */
export function FormActions({
  accent,
  loading,
  submitLabel,
  onCancel,
}: {
  accent: Accent;
  loading: boolean;
  submitLabel: string;
  onCancel: () => void;
}) {
  return (
    <div className="flex gap-3 pt-2">
      <button
        type="button"
        onClick={onCancel}
        className="flex-1 py-3 rounded-xl text-sm text-white/40 hover:text-white border border-white/[0.08]
                   hover:border-white/20 transition-all"
      >
        Cancel
      </button>
      <button
        type="submit"
        disabled={loading}
        className={`flex-1 py-3 rounded-xl text-sm font-semibold text-black disabled:opacity-50 transition-all ${ACCENT_STYLES[accent].solid}`}
      >
        {loading ? "Adding..." : submitLabel}
      </button>
    </div>
  );
}
