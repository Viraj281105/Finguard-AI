import { useId } from "react";

/** Centred card shared by the login and register pages. */
export default function AuthLayout({
  subtitle,
  footer,
  children,
}: {
  subtitle: string;
  footer: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-emerald-400">FinGuard AI</h1>
          <p className="text-gray-400 mt-2">{subtitle}</p>
        </div>

        <div className="bg-gray-900 rounded-2xl p-8 shadow-xl border border-gray-800">
          {children}
          <p className="text-center text-gray-400 mt-6 text-sm">{footer}</p>
        </div>
      </div>
    </div>
  );
}

export function AuthField({
  label,
  ...inputProps
}: { label: string } & React.InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-gray-300 mb-1">
        {label}
      </label>
      <input
        id={id}
        required
        {...inputProps}
        className="w-full bg-gray-800 text-white rounded-lg px-4 py-3 border border-gray-700 focus:outline-none focus:border-emerald-500"
      />
    </div>
  );
}

export function AuthSubmit({ loading, idle, busy }: { loading: boolean; idle: string; busy: string }) {
  return (
    <button
      type="submit"
      disabled={loading}
      className="w-full bg-emerald-500 hover:bg-emerald-600 disabled:opacity-50 text-white font-semibold py-3 rounded-lg transition-colors"
    >
      {loading ? busy : idle}
    </button>
  );
}
