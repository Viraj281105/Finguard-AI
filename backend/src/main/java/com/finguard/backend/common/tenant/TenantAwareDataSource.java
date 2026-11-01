package com.finguard.backend.common.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * DataSource decorator that wraps returned connections in {@link TenantConnectionProxy}
 * to enforce transaction-scoped PostgreSQL Row-Level Security policies (S1-06, 06 §9).
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection target = super.getConnection();
        return TenantConnectionProxy.wrap(target);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection target = super.getConnection(username, password);
        return TenantConnectionProxy.wrap(target);
    }
}
