/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.model;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ibm.fhir.database.utils.api.IDatabaseAdapter;
import com.ibm.fhir.database.utils.api.ITransaction;
import com.ibm.fhir.database.utils.api.ITransactionProvider;
import com.ibm.fhir.database.utils.api.IVersionHistoryService;
import com.ibm.fhir.database.utils.api.LockException;

/**
 * Represents objects which are part of the database, but which do not belong to
 * a particular schema (like tablespace, for example).
 */
public abstract class DatabaseObject implements IDatabaseObject {

    private static final Logger logger = Logger.getLogger(DatabaseObject.class.getName());

    // Used to randomize a sleep after a deadlock failure
    private static final SecureRandom random = new SecureRandom();

    private final String objectName;
    private final DatabaseObjectType objectType;

    // tag map
    private final Map<String,String> tags = new HashMap<>();

    // the database objects we depend on
    private Set<IDatabaseObject> dependencies = new HashSet<>();

    // The application version this object applies to
    private final int version;

    /**
     * Public constructor
     *
     * @param objectName
     * @param objectType
     * @param version
     */
    public DatabaseObject(String objectName, DatabaseObjectType objectType, int version) {
        this.objectName = objectName;
        this.objectType = objectType;
        this.version = version;
    }

    /**
     * Get the collection of dependencies for this object
     * @return
     */
    public Collection<IDatabaseObject> getDependencies() {
        return Collections.unmodifiableCollection(this.dependencies);
    }

    @Override
    public int getVersion() {
        return this.version;
    }

    @Override
    public DatabaseObjectType getObjectType() {
        return this.objectType;
    }

    @Override
    public int hashCode() {
        return this.objectType.hashCode() + 37 * objectName.hashCode();
    }

    public String getObjectName() {
        return this.objectName;
    }

    @Override
    public String getTypeAndName() {
        return getObjectType().name() + ":" + getObjectName();
    }

    /**
     * Add the given tags to our tag map
     * @param tags
     */
    public void addTags(Map<String,String> tags) {
        this.tags.putAll(tags);
    }

    /**
     * Add the tagGroup/tagValue pair to the tags for this object
     * @param tagGroup
     * @param tagValue
     */
    @Override
    public void addTag(String tagGroup, String tagValue) {
        this.tags.put(tagGroup, tagValue);
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            throw new IllegalArgumentException("Object other is null");
        }

        if (other instanceof DatabaseObject) {
            DatabaseObject that = (DatabaseObject)other;
            return this.objectType == that.objectType
                    && this.objectName.equals(that.objectName);
        }
        else {
            throw new IllegalArgumentException("Object other is not a " + this.getClass().getName());
        }
    }

    /**
     * Return the unique name for this object
     * @return
     */
    @Override
    public String getName() {
        return this.objectName;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public void applyTx(IDatabaseAdapter target, ITransactionProvider tp, IVersionHistoryService vhs) {
        // Wrap the apply operation in its own transaction, as this is likely
        // being executed from a thread-pool. DB2 has some issues with deadlocks
        // on its catalog tables (SQLCODE=-911, SQLSTATE=40001, SQLERRMC=2) when
        // applying schema changes in parallel, so we need a little retry loop.
        int remainingAttempts = 10;
        while (remainingAttempts-- > 0) {
            try (ITransaction tx = tp.getTransaction()) {
                try {
                    applyVersion(target, vhs);
                    remainingAttempts = 0; // exit the retry loop
                }
                catch (LockException x) {
                    // Either a deadlock, or lock timeout, we allow the transaction to be
                    // tried again.
                    if (x.isDeadlock()) {
                        logger.warning("Deadlock detected processing: " + this.getTypeAndName() + " [remaining=" + remainingAttempts + "]");
                    }
                    else {
                        logger.warning("Lock timeout detected processing: " + this.getTypeAndName() + " [remaining=" + remainingAttempts + "]");
                    }
                    tx.setRollbackOnly();

                    if (remainingAttempts == 0) {
                        // end of the road on this one
                        logger.log(Level.SEVERE, "[FAILED] retries exhausted for: " + this.getTypeAndName());
                        throw x;
                    }
                }
                catch (Exception x) {
                    logger.log(Level.SEVERE, "[FAILED] " + this.getTypeAndName());
                    tx.setRollbackOnly();
                    throw x;
                }
            }

            // now we're outside the transaction, if we need to try again, then sleep
            // for a random period. This hopefully avoids things getting into lock-step
            // which may further increase the chance of a deadlock when we retry
            if (remainingAttempts > 0) {
                safeSleep();
            }
        }
    }

    /**
     * Apply the change, but only if it has a newer version than we already have
     * recorded in the database
     * @param target
     * @param vhs the service used to manage the version history table
     */
    @Override
    public void applyVersion(IDatabaseAdapter target, IVersionHistoryService vhs) {
        // TODO find a better way to track database-level type stuff (not schema-specific)
        if (vhs.applies("__DATABASE__", getObjectType().name(), getObjectName(), version)) {
            logger.info("Applying change [v" + version + "]: "+ this.getTypeAndName());

            // Apply this change to the target database
            apply(vhs.getVersion("__DATABASE__", getObjectType().name(), getObjectName()), target);

            // call back to the version history service to add the new version to the table
            // being used to track the change history
            vhs.addVersion("__DATABASE__", getObjectType().name(), getObjectName(), getVersion());
        }
    }

    /**
     * Sleep a random amount of time.
     */
    protected void safeSleep() {
        long ms = random.nextInt(5000);
        try {
            Thread.sleep(ms);
        }
        catch (InterruptedException ix) {
            // NOP
        }
    }

    @Override
    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(this.tags);
    }

}
