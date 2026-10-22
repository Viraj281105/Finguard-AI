import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiErrorMessage } from "../../shared/api/client";
import { setToken } from "../../shared/auth/tokenStorage";
import { authAPI } from "./api";
import AuthLayout, { AuthField, AuthSubmit } from "./AuthLayout";

export default function LoginPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ email: "", password: "" });
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await authAPI.login(form);
      setToken(res.accessToken);
      navigate("/dashboard");
    } catch (err) {
      setError(apiErrorMessage(err, "Invalid email or password"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      subtitle="Sign in to your account"
      footer={
        <>
          Don't have an account?{" "}
          <Link to="/register" className="text-emerald-400 hover:underline">
            Register
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <AuthField
          label="Email"
          type="email"
          value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })}
          placeholder="you@example.com"
        />
        <AuthField
          label="Password"
          type="password"
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          placeholder="••••••••"
        />

        {error && (
          <p role="alert" className="text-red-400 text-sm">
            {error}
          </p>
        )}

        <AuthSubmit loading={loading} idle="Sign In" busy="Signing in..." />
      </form>
    </AuthLayout>
  );
}
