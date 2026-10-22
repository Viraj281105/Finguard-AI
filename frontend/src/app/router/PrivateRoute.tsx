import { Navigate } from "react-router-dom";
import { isAuthenticated } from "../../shared/auth/tokenStorage";

/** Renders its children only for a signed-in user (stored, unexpired token); otherwise redirects to /login. */
export default function PrivateRoute({ children }: { children: React.ReactNode }) {
  return isAuthenticated() ? <>{children}</> : <Navigate to="/login" replace />;
}
