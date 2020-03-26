/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.schema.control;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

import com.ibm.fhir.database.utils.api.IDatabaseStatement;
import com.ibm.fhir.database.utils.api.IDatabaseTranslator;
import com.ibm.fhir.schema.model.ResourceType;

/**
 * Manages the DB2 Get Resource Types
 */
public class GetResourceTypes implements IDatabaseStatement {
    private final String schemaName;
    private final Consumer<ResourceType> consumer;

    public GetResourceTypes(String schemaName, Consumer<ResourceType> c) {
        this.schemaName = schemaName;
        this.consumer = c;
    }

    @Override
    public void run(IDatabaseTranslator translator, Connection c) {
        final String SQL = "SELECT resource_type_id, resource_type " 
                         + "  FROM " + schemaName + ".RESOURCE_TYPES";

        try (Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(SQL);
            while (rs.next()) {
                ResourceType rt = new ResourceType();
                rt.setId(rs.getLong(1));
                rt.setName(rs.getString(2));
                consumer.accept(rt);
            }
        } catch (SQLException x) {
            throw translator.translate(x);
        }

    }

}