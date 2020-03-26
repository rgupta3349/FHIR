/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.version;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import com.ibm.fhir.database.utils.api.IDatabaseSupplier;
import com.ibm.fhir.database.utils.api.IDatabaseTranslator;
import com.ibm.fhir.database.utils.common.DataDefinitionUtil;

/**
 * Get the latest version by object type and name. This is important, because
 * we deploy the schema in parallel, and so certain objects might end up with
 * different versions at different times (in case of failures)
 * <br>
 * The <code>Map&lt;String,Integer&rt;</code> returned from {@link #run(IDatabaseTranslator, Connection)}
 * uses a compound string of type:name for the key e.g. "Table:PATIENT_RESOURCES".
 *
 */
public class GetLatestVersionDAO implements IDatabaseSupplier<Map<String,Integer>> {
    private final String adminSchemaName;
    private final String schemaName;

    /**
     * Public constructor
     *
     * @param adminSchemaName
     * @param schemaName
     */
    public GetLatestVersionDAO(String adminSchemaName, String schemaName) {
        this.adminSchemaName = adminSchemaName;
        this.schemaName = schemaName;
    }

    @Override
    public Map<String,Integer> run(IDatabaseTranslator translator, Connection c) {
        Map<String,Integer> result = new HashMap<>();
        final String tbl = DataDefinitionUtil.getQualifiedName(adminSchemaName, SchemaConstants.VERSION_HISTORY);
        final String cols = DataDefinitionUtil.join(SchemaConstants.SCHEMA_NAME, SchemaConstants.OBJECT_TYPE, SchemaConstants.OBJECT_NAME);
        final String sql = "SELECT " + cols
                + ", max(" + SchemaConstants.VERSION + ") FROM " + tbl
                + " WHERE " + SchemaConstants.SCHEMA_NAME + " IN (?, ?) "
                + " GROUP BY " + cols;

        // Fetch the current version history information for both the admin and specified data schemas
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, adminSchemaName);
            ps.setString(2, schemaName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String schema = rs.getString(1);
                String type = rs.getString(2);
                String name = rs.getString(3);
                int version = rs.getInt(4);

                String schemaTypeName = schema + ":" + type + ":" + name;
                result.put(schemaTypeName, version);
            }
        }
        catch (SQLException x) {
            throw translator.translate(x);
        }

        return result;
    }

}
