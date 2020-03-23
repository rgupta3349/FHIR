/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import com.ibm.fhir.database.utils.api.IDatabaseStatement;
import com.ibm.fhir.database.utils.api.IDatabaseTranslator;

/**
 * Drop columns from the schema.table
 */
public class DropForeignKeyConstraint implements IDatabaseStatement {
    private final String schemaName;
    private final String tableName;
    private final List<String> constraintNames;

    /**
     * Public constructor
     * @param schemaName
     * @param tableName
     */
    public DropForeignKeyConstraint(String schemaName, String tableName, String... constraintName) {
        DataDefinitionUtil.assertValidName(schemaName);
        DataDefinitionUtil.assertValidName(tableName);
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.constraintNames = Arrays.asList(constraintName);
    }

    @Override
    public void run(IDatabaseTranslator translator, Connection c) {
        final String qname = DataDefinitionUtil.getQualifiedName(schemaName, tableName);
        final StringBuilder ddl = new StringBuilder("ALTER TABLE " + qname);
        for (String constraintName : constraintNames) {
            ddl.append("\n\t" + "DROP FOREIGN KEY " + constraintName);
        }

        try (Statement s = c.createStatement()) {
            s.executeUpdate(ddl.toString());
        }
        catch (SQLException x) {
            throw translator.translate(x);
        }
    }

}
