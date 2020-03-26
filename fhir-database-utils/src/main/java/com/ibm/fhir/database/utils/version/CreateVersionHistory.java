/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.version;

import com.ibm.fhir.database.utils.api.IDatabaseAdapter;
import com.ibm.fhir.database.utils.model.PhysicalDataModel;
import com.ibm.fhir.database.utils.model.Table;

/**
 * We don't add the version_history to the {@link PhysicalDataModel} because
 * it's a schema management table, and the model shouldn't really care about
 * it.
 */
public class CreateVersionHistory {

    /**
     * Create the version history table in the given (admin) schema. This
     * table is used to determine the current version of the schema, and
     * hence which changes should be applied in order to migrate the
     * schema to the latest version.
     * <br>
     * As this is the version table itself, it is assigned a version id
     * of 0 which is used to indicate that versioning doesn't apply.
     *
     * @param adminSchemaName
     * @param target
     */
    public static void createTableIfNeeded(String adminSchemaName, IDatabaseAdapter target) {
        PhysicalDataModel dataModel = new PhysicalDataModel();
        Table t = Table.builder(adminSchemaName, SchemaConstants.VERSION_HISTORY)
                .setVersion(0)
                .addVarcharColumn(SchemaConstants.SCHEMA_NAME, 64, false)
                .addVarcharColumn(SchemaConstants.OBJECT_TYPE, 16, false)
                .addVarcharColumn(SchemaConstants.OBJECT_NAME, 64, false)
                .addIntColumn(SchemaConstants.VERSION, false)
                .addTimestampColumn(SchemaConstants.APPLIED, false)
                .addPrimaryKey("PK_" + SchemaConstants.VERSION_HISTORY, SchemaConstants.SCHEMA_NAME, SchemaConstants.OBJECT_TYPE, SchemaConstants.OBJECT_NAME, SchemaConstants.VERSION)
                .build(dataModel);
        dataModel.addTable(t);
        dataModel.addObject(t);

        // apply this data model to the target if necessary - note - this bypasses the
        // version history table...because this is the table we're trying to create!
        if (!t.exists(target)) {
            dataModel.apply(target);
        }
    }

}
