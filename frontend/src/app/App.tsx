import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import DashboardPage from "../features/dashboard/DashboardPage";
import InvestmentsPage from "../features/investments/InvestmentsPage";
import LoansPage from "../features/loans/LoansPage";
import TransactionsPage from "../features/transactions/TransactionsPage";
import PrivateRoute from "./router/PrivateRoute";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* Protected */}
        <Route path="/dashboard" element={<PrivateRoute><DashboardPage /></PrivateRoute>} />
        <Route path="/transactions" element={<PrivateRoute><TransactionsPage /></PrivateRoute>} />
        <Route path="/investments" element={<PrivateRoute><InvestmentsPage /></PrivateRoute>} />
        <Route path="/loans" element={<PrivateRoute><LoansPage /></PrivateRoute>} />

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
