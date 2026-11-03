package com.finguard.backend.common.tenant;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates dynamic proxies around JDBC Connections that enforce PostgreSQL
 * transaction-scoped Row-Level Security (S1-06, 06 §9).
 */
@Slf4j
public final class TenantConnectionProxy implements InvocationHandler {

    private static volatile Boolean roleExistsCached = null;

    private final Connection target;
    private boolean contextApplied = false;

    private TenantConnectionProxy(Connection target) {
        this.target = target;
    }

    public static Connection wrap(Connection target) {
        if (target == null) {
            return null;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                new TenantConnectionProxy(target));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("createStatement".equals(name) || "prepareStatement".equals(name) || "prepareCall".equals(name)) {
            applyTenantContext();
        } else if ("commit".equals(name) || "rollback".equals(name)) {
            contextApplied = false;
        } else if ("setAutoCommit".equals(name)) {
            contextApplied = false;
        } else if ("close".equals(name)) {
            resetSessionStateIfAutoCommit();
            contextApplied = false;
        } else if ("unwrap".equals(name) && args != null && args.length == 1) {
            Class<?> iface = (Class<?>) args[0];
            if (iface.isInstance(proxy)) {
                return proxy;
            }
            if (iface.isInstance(target)) {
                return target;
            }
        } else if ("isWrapperFor".equals(name) && args != null && args.length == 1) {
            Class<?> iface = (Class<?>) args[0];
            if (iface.isInstance(proxy) || iface.isInstance(target)) {
                return true;
            }
        }

        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private void applyTenantContext() throws SQLException {
        if (contextApplied) {
            return;
        }
        try {
            if (target.isClosed()) {
                return;
            }

            boolean isLocal = !target.getAutoCommit();
            boolean bypass = TenantContext.isBypassRls();
            String tenantId = TenantContext.getCurrentTenantId();

            if (bypass) {
                try (Statement stmt = target.createStatement()) {
                    stmt.execute("SELECT set_config('app.bypass_rls', 'on', " + isLocal + ")");
                }
            } else if (tenantId != null) {
                if (shouldSwitchToAppRole(target)) {
                    try (Statement setRoleStmt = target.createStatement()) {
                        String sql = isLocal ? "SET LOCAL ROLE finguard_app" : "SET ROLE finguard_app";
                        setRoleStmt.execute(sql);
                    }
                }

                if (!tenantId.isBlank()) {
                    try (PreparedStatement stmt = target.prepareStatement(
                            "SELECT set_config('app.current_user_id', ?, " + isLocal + ")")) {
                        stmt.setString(1, tenantId);
                        stmt.execute();
                    }
                } else {
                    try (Statement stmt = target.createStatement()) {
                        stmt.execute("SELECT set_config('app.current_user_id', '', " + isLocal + ")");
                    }
                }
            }
            contextApplied = true;
        } catch (SQLException e) {
            // Fail closed: if the tenant context cannot be set, the statement must not run. Continuing would execute it
            // with no tenant restriction (a superuser connection bypasses RLS entirely), i.e. fail open.
            log.error("Could not apply the tenant context to the JDBC connection; refusing to run the statement: {}",
                    e.getMessage());
            throw new SQLException("Could not apply the tenant context to the database connection", e);
        }
    }

    private void resetSessionStateIfAutoCommit() {
        try {
            if (contextApplied && !target.isClosed() && target.getAutoCommit()) {
                try (Statement stmt = target.createStatement()) {
                    stmt.execute(
                            "RESET ROLE; SELECT set_config('app.current_user_id', '', false); SELECT set_config('app.bypass_rls', '', false);");
                }
            }
        } catch (SQLException ignored) {
        }
    }

    private boolean shouldSwitchToAppRole(Connection conn) {
        try {
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT current_setting('is_superuser', true)")) {
                if (!rs.next() || !"on".equalsIgnoreCase(rs.getString(1))) {
                    return false;
                }
            }

            if (roleExistsCached == null || !roleExistsCached) {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'finguard_app'")) {
                    roleExistsCached = rs.next();
                }
            }
            return Boolean.TRUE.equals(roleExistsCached);
        } catch (SQLException e) {
            return false;
        }
    }
}
