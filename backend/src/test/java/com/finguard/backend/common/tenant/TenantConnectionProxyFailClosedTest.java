package com.finguard.backend.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Row-level security must fail closed: if the tenant context cannot be applied to the connection, the caller's
 * statement must not run (it would otherwise execute unrestricted, because a superuser connection bypasses RLS).
 */
class TenantConnectionProxyFailClosedTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** A connection whose tenant-context statements fail; it records every other statement it is asked to prepare. */
    private static Connection brokenConnection(List<String> executedSql) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return false;
                        case "getAutoCommit":
                            return false;
                        case "createStatement":
                            throw new SQLException("simulated failure");
                        case "prepareStatement":
                            String sql = (String) args[0];
                            if (sql.contains("set_config")) {
                                throw new SQLException("simulated failure");
                            }
                            executedSql.add(sql);
                            return null;
                        default:
                            return null;
                    }
                });
    }

    @Test
    void aStatementIsRefusedWhenTheTenantContextCannotBeApplied() {
        List<String> executed = new ArrayList<>();
        Connection connection = TenantConnectionProxy.wrap(brokenConnection(executed));
        TenantContext.setCurrentTenantId("user-1");

        assertThatThrownBy(() -> connection.prepareStatement("select * from transactions"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("tenant context");

        assertThat(executed).as("the caller's SQL must never reach the database").isEmpty();
    }
}
