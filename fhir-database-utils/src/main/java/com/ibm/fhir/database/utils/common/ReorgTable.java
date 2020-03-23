/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.ibm.fhir.database.utils.api.IDatabaseStatement;
import com.ibm.fhir.database.utils.api.IDatabaseTranslator;

/**
 * Reorg the schema.table
 */
public class ReorgTable implements IDatabaseStatement {
    private final String schemaName;
    private final String tableName;

    /**
     * Public constructor
     * @param schemaName
     * @param tableName
     */
    public ReorgTable(String schemaName, String tableName) {
        DataDefinitionUtil.assertValidName(schemaName);
        DataDefinitionUtil.assertValidName(tableName);
        this.schemaName = schemaName;
        this.tableName = tableName;
    }

    @Override
    public void run(IDatabaseTranslator translator, Connection c) {
        final String qname = DataDefinitionUtil.getQualifiedName(schemaName, tableName);
        final String ddl = "CALL SYSPROC.ADMIN_CMD ('REORG TABLE " + qname + "')";

        try (Statement s = c.createStatement()) {
            s.executeUpdate(ddl);
        }
        catch (SQLException x) {
            throw translator.translate(x);
        }
    }

}
