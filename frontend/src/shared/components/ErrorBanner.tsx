export default function ErrorBanner({
  message,
  className = "mb-4",
}: {
  message: string;
  className?: string;
}) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className={`${className} px-4 py-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400 text-sm`}
    >
      {message}
    </div>
  );
}
