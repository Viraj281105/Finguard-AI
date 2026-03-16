import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";

// Pages — adjust these import paths if your existing pages live elsewhere
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import DashboardPage from "./pages/DashboardPage";
import TransactionsPage from "./pages/TransactionsPage";
import InvestmentsPage from "./pages/InvestmentsPage";
import LoansPage from "./pages/LoansPage";

// ── Auth guard ────────────────────────────────────────────────────────────────
function PrivateRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem("token");
  return token ? <>{children}</> : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login"    element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* Protected */}
        <Route
          path="/dashboard"
          element={<PrivateRoute><DashboardPage /></PrivateRoute>}
        />
        <Route
          path="/transactions"
          element={<PrivateRoute><TransactionsPage /></PrivateRoute>}
        />
        <Route
          path="/investments"
          element={<PrivateRoute><InvestmentsPage /></PrivateRoute>}
        />
        <Route
          path="/loans"
          element={<PrivateRoute><LoansPage /></PrivateRoute>}
        />

        {/* Fallback */}
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
