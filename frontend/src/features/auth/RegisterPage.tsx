import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiErrorMessage } from "../../shared/api/client";
import { setToken } from "../../shared/auth/tokenStorage";
import { authAPI } from "./api";
import AuthLayout, { AuthField, AuthSubmit } from "./AuthLayout";

export default function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: "", email: "", password: "" });
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await authAPI.register(form);
      setToken(res.accessToken);
      navigate("/dashboard");
    } catch (err) {
      setError(apiErrorMessage(err, "Registration failed"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      subtitle="Create your account"
      footer={
        <>
          Already have an account?{" "}
          <Link to="/login" className="text-emerald-400 hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        <AuthField
          label="Full Name"
          type="text"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="Viraj Jadhao"
        />
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
          minLength={10}
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          placeholder="At least 10 characters"
        />

        {error && (
          <p role="alert" className="text-red-400 text-sm">
            {error}
          </p>
        )}

        <AuthSubmit loading={loading} idle="Create Account" busy="Creating account..." />
      </form>
    </AuthLayout>
  );
}
