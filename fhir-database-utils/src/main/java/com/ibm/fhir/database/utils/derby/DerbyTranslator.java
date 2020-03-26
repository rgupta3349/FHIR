/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.derby;

import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import com.ibm.fhir.database.utils.api.ConnectionDetails;
import com.ibm.fhir.database.utils.api.ConnectionException;
import com.ibm.fhir.database.utils.api.DataAccessException;
import com.ibm.fhir.database.utils.api.IDatabaseTranslator;
import com.ibm.fhir.database.utils.api.LockException;
import com.ibm.fhir.database.utils.api.UndefinedNameException;
import com.ibm.fhir.database.utils.api.UniqueConstraintViolationException;

/**
 * translates database access to Derby supported access.
 */
public class DerbyTranslator implements IDatabaseTranslator {
    private static final Logger logger = Logger.getLogger(DerbyTranslator.class.getName());

    @Override
    public String addForUpdate(String sql) {
        return sql + " FOR UPDATE";
    }

    @Override
    public boolean isDerby() {
        return true;
    }

    @Override
    public String globalTempTableName(String tableName) {
        return "SYSTEM." + tableName;
    }

    @Override
    public String createGlobalTempTable(String ddl) {
        return "DECLARE " + ddl;
    }

    @Override
    public boolean isDuplicate(SQLException x) {
        // Class Code 23: Constraint Violation
        return "23505".equals(x.getSQLState());
    }

    @Override
    public boolean isAlreadyExists(SQLException x) {
        return "42710".equals(x.getSQLState());
    }

    @Override
    public boolean isLockTimeout(SQLException x) {
        return false;
    }

    @Override
    public boolean isDeadlock(SQLException x) {
        final String sqlState = x.getSQLState();
        return "40XL1".equals(sqlState) || "40XL2".equals(sqlState);
    }

    @Override
    public boolean isConnectionError(SQLException x) {
        String sqlState = x.getSQLState();
        return sqlState != null && sqlState.startsWith("08");
    }

    @Override
    public DataAccessException translate(SQLException x) {
        if (isDeadlock(x)) {
            return new LockException(x, true);
        }
        else if (isLockTimeout(x)) {
            return new LockException(x, false);
        }
        else if (isConnectionError(x)) {
            return new ConnectionException(x);
        }
        else if (isDuplicate(x)) {
            return new UniqueConstraintViolationException(x);
        }
        else if (isUndefinedName(x)) {
            return new UndefinedNameException(x);
        }
        else {
            return new DataAccessException(x);
        }
    }

    @Override
    public boolean isUndefinedName(SQLException x) {
        return "42X05".equals(x.getSQLState());
    }

    @Override
    public void fillProperties(Properties p, ConnectionDetails cd) {
        p.put("user", cd.getUser());
        p.put("password", cd.getPassword());

        if (cd.isSsl()) {
            p.put("sslConnection", "true");
        }

        if (cd.isHA()) {
            logger.warning("No HA support for Derby");
        }
    }

    @Override
    public String timestampDiff(String left, String right, String alias) {
        if (alias == null || alias.isEmpty()) {
            return String.format("{fn timestampdiff(SQL_TSI_SECOND, %s, %s)}", left, right);
        }
        else {
            return String.format("{fn timestampdiff(SQL_TSI_SECOND, %s, %s)} AS %s", left, right, alias);
        }
    }

    @Override
    public String createSequence(String name, int cache) {
        // cache isn't supported by Derby
        return "CREATE SEQUENCE " + name;
    }

    @Override
    public String reorgTableCommand(String tableName) {
        // REORG TABLE not supported by Derby, so return null
        return null;
    }

    @Override
    public String getDriverClassName() {
        return "org.apache.derby.jdbc.EmbeddedDriver";
    }

    @Override
    public String getUrl(Properties connectionProperties) {
        StringBuilder url = new StringBuilder("jdbc:derby:");
        DerbyPropertyAdapter adapter = new DerbyPropertyAdapter(connectionProperties);
        if (adapter.isMemory()) {
            url.append("memory:");
        }
        url.append(adapter.getDatabase());
        if (adapter.isAutoCreate()) {
            url.append(";create=true");
        }
        return url.toString();
    }

    @Override
    public boolean clobSupportsInline() {
        return false;
    }
}
