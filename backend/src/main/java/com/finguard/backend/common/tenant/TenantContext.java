package com.finguard.backend.common.tenant;

import java.util.concurrent.Callable;

/**
 * Thread-local context carrying the current tenant (user ID) and system bypass flag (S1-06, 06 §9).
 * Propagated to PostgreSQL transactions as {@code app.current_user_id} via {@code SET LOCAL}.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS_RLS = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setCurrentTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            CURRENT_TENANT.set(tenantId.trim());
        } else {
            CURRENT_TENANT.remove();
        }
    }

    public static String getCurrentTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void setBypassRls(boolean bypass) {
        if (bypass) {
            BYPASS_RLS.set(Boolean.TRUE);
        } else {
            BYPASS_RLS.remove();
        }
    }

    public static boolean isBypassRls() {
        return Boolean.TRUE.equals(BYPASS_RLS.get());
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        BYPASS_RLS.remove();
    }

    public static void runAs(String tenantId, Runnable action) {
        String prevTenant = CURRENT_TENANT.get();
        Boolean prevBypass = BYPASS_RLS.get();
        setCurrentTenantId(tenantId);
        BYPASS_RLS.remove();
        try {
            action.run();
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevBypass != null) {
                BYPASS_RLS.set(prevBypass);
            } else {
                BYPASS_RLS.remove();
            }
        }
    }

    public static <T> T callAs(String tenantId, Callable<T> action) {
        String prevTenant = CURRENT_TENANT.get();
        Boolean prevBypass = BYPASS_RLS.get();
        setCurrentTenantId(tenantId);
        BYPASS_RLS.remove();
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Tenant-scoped operation failed", e);
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevBypass != null) {
                BYPASS_RLS.set(prevBypass);
            } else {
                BYPASS_RLS.remove();
            }
        }
    }

    public static void runAsSystem(Runnable action) {
        String prevTenant = CURRENT_TENANT.get();
        Boolean prevBypass = BYPASS_RLS.get();
        setBypassRls(true);
        CURRENT_TENANT.remove();
        try {
            action.run();
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevBypass != null) {
                BYPASS_RLS.set(prevBypass);
            } else {
                BYPASS_RLS.remove();
            }
        }
    }

    public static <T> T callAsSystem(Callable<T> action) {
        String prevTenant = CURRENT_TENANT.get();
        Boolean prevBypass = BYPASS_RLS.get();
        setBypassRls(true);
        CURRENT_TENANT.remove();
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("System-scoped operation failed", e);
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevBypass != null) {
                BYPASS_RLS.set(prevBypass);
            } else {
                BYPASS_RLS.remove();
            }
        }
    }
}

