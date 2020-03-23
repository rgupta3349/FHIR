/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.version;

import java.util.Collection;
import java.util.Map;

import com.ibm.fhir.database.utils.api.DataAccessException;
import com.ibm.fhir.database.utils.api.IConnectionProvider;
import com.ibm.fhir.database.utils.api.IDatabaseAdapter;
import com.ibm.fhir.database.utils.api.IDatabaseTarget;
import com.ibm.fhir.database.utils.api.ITransaction;
import com.ibm.fhir.database.utils.api.ITransactionProvider;
import com.ibm.fhir.database.utils.api.IVersionHistoryService;

/**
 * Encapsulation of the transaction needed to read the version history table
 */
public class VersionHistoryService implements IVersionHistoryService {

    // The name of the admin schema we are working with
    private final String adminSchemaName;

    // The name of the data schema we are working with
    private final String schemaName;

    // Allows us to start a transaction
    private ITransactionProvider transactionProvider;

    // The target representing the database we want to interact with
    private IDatabaseAdapter target;

    // The map of version history information loaded from the database
    private Map<String,Integer> versionHistoryMap;

    public VersionHistoryService(String adminSchemaName, String schemaName) {
        this.adminSchemaName = adminSchemaName;
        this.schemaName = schemaName;
    }

    /**
     * For injection of the {@link IConnectionProvider}
     * @param tp
     */
    public void setTransactionProvider(ITransactionProvider tp) {
        this.transactionProvider = tp;
    }

    /**
     * For injection of the {@link IDatabaseTarget}
     * @param tgt
     */
    public void setTarget(IDatabaseAdapter tgt) {
        this.target = tgt;
    }

    /**
     * Fetch the version history map for the given schema. This then
     * becomes the reference for everything we try to apply going
     * forward. This assumes, of course. If someone else comes along
     * and tries to update the schema after we read this map, then
     * it's possible (likely) that we'll try to apply a change that
     * is no longer required, which is probably going to end in tears.
     * But it's OK. A second attempt will see that the change has
     * already been applied, so won't try again.
     */
    public void init() {
        // defend
        if (this.target == null) {
            throw new IllegalStateException("Programming error - must setTarget before calling init");
        }

        if (transactionProvider != null) {
            try (ITransaction tx = transactionProvider.getTransaction()) {
                try {
                    // Note how we don't care about connections here...that is all
                    // hidden inside the target adapter implementation
                    GetLatestVersionDAO dao = new GetLatestVersionDAO(adminSchemaName, schemaName);
                    this.versionHistoryMap = target.runStatement(dao);
                }
                catch (DataAccessException x) {
                    // Something went wrong, so mark the transaction as failed
                    tx.setRollbackOnly();
                    throw x;
                }
            }
        }
        else {
            // Assume the parent is responsible for handling the transaction
            GetLatestVersionDAO dao = new GetLatestVersionDAO(adminSchemaName, schemaName);
            this.versionHistoryMap = target.runStatement(dao);
        }
    }


    /**
     * Insert all the entries in the versionHistoryMap. This must be called in the
     * context of an existing transaction
     * @param versionHistories
     */
    public void insertVersionHistoriesInTx(Collection<TypeNameVersion> versionHistories) {
        for (TypeNameVersion tuple: versionHistories) {
            insertVersionHistoryInTx(tuple.getSchema(), tuple.getType(), tuple.getName(), tuple.getVersion());
        }
    }

    /**
     * Insert the version history for the objectType/objectName/version.
     *
     * @param objectSchema
     * @param objectType
     * @param objectName
     * @param version
     */
    public void insertVersionHistoryInTx(String objectSchema, String objectType, String objectName, int version) {
        AddVersionDAO dao = new AddVersionDAO(adminSchemaName, objectSchema, objectType, objectName, version);
        target.runStatement(dao);
    }

    /**
     * Insert all the entries in the versionHistoryMap in a new transaction (useful
     * for testing).
     * @param versionHistories
     */
    public void insertVersionHistory(Collection<TypeNameVersion> versionHistories) {
        try (ITransaction tx = transactionProvider.getTransaction()) {
            try {
                insertVersionHistoriesInTx(versionHistories);
            }
            catch (DataAccessException x) {
                // Something went wrong, so mark the transaction as failed
                tx.setRollbackOnly();
                throw x;
            }
        }
    }

    /**
     * Factory method for creating a {@link TypeNameVersion} tuple
     *
     * @param objectSchema
     * @param type
     * @param name
     * @param version
     * @return
     */
    public static TypeNameVersion createTypeNameVersion(String objectSchema, String type, String name, int version) {
        return new TypeNameVersion(objectSchema, type, name, version);
    }

    public static class TypeNameVersion {
        private final String schema;
        private final String type;
        private final String name;
        private final int version;

        private TypeNameVersion(String schema, String type, String name, int version) {
            this.schema = schema;
            this.type = type;
            this.name = name;
            this.version = version;
        }

        private String getSchema() {
            return this.schema;
        }

        private String getType() {
            return this.type;
        }

        private String getName() {
            return this.name;
        }

        private int getVersion() {
            return this.version;
        }
    }

    @Override
    public Integer getVersion(String objectSchema, String objectType, String objectName) {
        String key = objectSchema + ":" + objectType + ":" + objectName;
        return versionHistoryMap.containsKey(key) ? versionHistoryMap.get(key) : 0;
    }

    @Override
    public void addVersion(String objectSchema, String objectType, String objectName, int version) {
        insertVersionHistoryInTx(objectSchema, objectType, objectName, version);
    }

    @Override
    public boolean applies(String objectSchema, String objectType, String objectName, int changeVersion) {
        String key = objectSchema + ":" + objectType + ":" + objectName;
        Integer currentVersion = this.versionHistoryMap.get(key);
        return currentVersion == null || currentVersion < changeVersion;
    }
}
