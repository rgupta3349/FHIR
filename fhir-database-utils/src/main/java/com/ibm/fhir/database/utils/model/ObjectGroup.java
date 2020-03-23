/*
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.database.utils.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ibm.fhir.database.utils.api.IDatabaseAdapter;
import com.ibm.fhir.database.utils.api.IVersionHistoryService;

/**
 * A collection of {@link IDatabaseObject} which are applied in order within one transaction
 */
public class ObjectGroup extends BaseObject {

    // the list of objects in our group
    private final List<IDatabaseObject> group = new ArrayList<>();

    /**
     * Public constructor
     *
     * @param schemaName
     * @param name
     * @param groupIn
     */
    public ObjectGroup(String schemaName, String name, Collection<IDatabaseObject> groupIn) {
        // not a real database object, so gets the special version of 0
        super(schemaName, name, DatabaseObjectType.GROUP, 0);
        this.group.addAll(groupIn);

        // Make this ObjectGroup depend on everything outside of this group that our individual
        // children depend on
        Set<IDatabaseObject> groupSet = new HashSet<>();
        groupSet.addAll(this.group);

        List<IDatabaseObject> deps = new ArrayList<>();
        for (IDatabaseObject obj: group) {
            List<IDatabaseObject> memberDeps = new ArrayList<>();
            obj.fetchDependenciesTo(memberDeps);

            // Check each of the member's dependencies, only adding them as
            // a dependency of this group if they aren't part of the group itself.
            for (IDatabaseObject d: memberDeps) {
                if (!groupSet.contains(d)) {
                    deps.add(d);
                }
            }
        }

        addDependencies(deps);
    }

    @Override
    public void applyVersion(IDatabaseAdapter target, IVersionHistoryService vhs) {

        // Apply each member of our group to the target if it is a new version.
        // Version tracking is done at the individual level, not the group.
        for (IDatabaseObject obj: this.group) {
            obj.applyVersion(target, vhs);
        }
    }

    @Override
    public void drop(IDatabaseAdapter target) {
        // Apply each member of the group, but going in reverse
        for (int i=group.size()-1; i>=0; i--) {
            group.get(i).drop(target);
        }
    }

    @Override
    public void grant(IDatabaseAdapter target, String groupName, String toUser) {
        /**
         * Override the BaseObject behavior because we need to propagate the grant request
         * to the indivual objects we have aggregated
         */

        for (IDatabaseObject obj: this.group) {
            obj.grant(target, groupName, toUser);
        }
    }

    @Override
    public void apply(IDatabaseAdapter target) {
        // Plain old apply, used to apply all changes, regardless of version - e.g. for testing
        for (IDatabaseObject obj: this.group) {
            obj.apply(target);
        }
    }

    @Override
    public void apply(Integer priorVersion, IDatabaseAdapter target) {
        // Plain old apply, used to apply all changes, regardless of version - e.g. for testing
        for (IDatabaseObject obj: this.group) {
            obj.apply(priorVersion, target);
        }
    }
}
