export type Accent = "cyan" | "violet" | "rose";

/**
 * Per-feature accent colours. Class names are written out in full (never assembled from
 * fragments) so Tailwind can see them at build time.
 */
export const ACCENT_STYLES: Record<
  Accent,
  { spinner: string; solid: string; selected: string; focusBorder: string }
> = {
  cyan: {
    spinner: "border-[#00d4ff]/30 border-t-[#00d4ff]",
    solid: "bg-[#00d4ff] hover:bg-[#00b8d9]",
    selected: "bg-[#00d4ff]/20 text-[#00d4ff] border-[#00d4ff]/30",
    focusBorder: "focus:border-[#00d4ff]/40",
  },
  violet: {
    spinner: "border-[#a78bfa]/30 border-t-[#a78bfa]",
    solid: "bg-[#a78bfa] hover:bg-[#9061f9]",
    selected: "bg-[#a78bfa]/20 text-[#a78bfa] border-[#a78bfa]/30",
    focusBorder: "focus:border-[#a78bfa]/40",
  },
  rose: {
    spinner: "border-[#fb7185]/30 border-t-[#fb7185]",
    solid: "bg-[#fb7185] hover:bg-[#f43f5e]",
    selected: "bg-[#fb7185]/20 text-[#fb7185] border-[#fb7185]/30",
    focusBorder: "focus:border-[#fb7185]/40",
  },
};

/** Class names shared by every text/number/date/select control in the add-forms. */
export function inputClass(accent: Accent, variant: "input" | "select" = "input"): string {
  const background = variant === "select" ? "bg-[#0f1117]" : "bg-white/[0.04]";
  return `w-full ${background} border border-white/[0.08] rounded-xl px-4 py-3 text-sm text-white placeholder-white/20 focus:outline-none ${ACCENT_STYLES[accent].focusBorder} transition-colors`;
}
