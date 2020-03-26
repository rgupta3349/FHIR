/*
 * (C) Copyright IBM Corp. 2017, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.persistence.jdbc.dao.api;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import com.ibm.fhir.persistence.context.FHIRPersistenceContext;
import com.ibm.fhir.persistence.exception.FHIRPersistenceException;
import com.ibm.fhir.persistence.exception.FHIRPersistenceVersionIdMismatchException;
import com.ibm.fhir.persistence.jdbc.dto.ExtractedParameterValue;
import com.ibm.fhir.persistence.jdbc.dto.Resource;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDBConnectException;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDataAccessException;
import com.ibm.fhir.persistence.jdbc.util.SqlQueryData;

/**
 * This Data Access Object interface provides methods creating, updating, and retrieving rows in the FHIR Resource tables.
 */
public interface ResourceDAO extends FHIRDbDAO {

    /**
     * Reads and returns the latest version of the Resource with the passed logical id and resource type.
     * If no matching resource is found, null is returned.
     * @param logicalId
     * @param resourceType
     * @return Resource - The most recent version of the Resource with the passed logical id and resource type, or null if not found.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    Resource read(String logicalId, String resourceType)
            throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Reads and returns the version of the Resource with the passed logical id, resource type, and version id.
     * If no matching resource is found, null is returned.
     * @param logicalId
     * @param resourceType
     * @param version id
     * @return Resource - The version of the Resource with the passed logical id, resource type, and version id or null if not found.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    Resource versionRead(String logicalId, String resourceType, int versionId)
            throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Reads and returns all versions of the Resource with the passed logicalId, ordered by descending version id.
     * If non-null, the passed fromDateTime is used to limit the returned Resource
     * versions to those that were updated after the fromDateTime.
     * @param resourceType - The name of a FHIR Resource type
     * @param logicalId - The logical id of a FHIR Resource
     * @param fromDateTime - The starting date/time of the version history.
     * @return List<Resource> - An ordered list of Resource versions.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    List<Resource> history(String resourceType, String logicalId, Timestamp fromDateTime, int offset, int maxResults)
            throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Reads and returns the COUNT of all versions of the Resource with the passed logicalId.
     * If non-null, the passed fromDateTime is used to limit the count of Resource versions to those that were updated after the fromDateTime.
     * @param resourceType - The name of a FHIR Resource type
     * @param logicalId - The logical id of a FHIR Resource
     * @param fromDateTime - The starting date/time of the version history.
     * @return int - The count of Resource versions.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    int historyCount(String resourceType, String logicalId, Timestamp fromDateTime)
            throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Executes the search contained in the passed SqlQueryData, using it's encapsulated search string and bind variables.
     * @param queryData - Contains a search string and (optionally) bind variables.
     * @return List<Resource> A list of FHIR Resources satisfying the passed search.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    List<Resource> search(SqlQueryData queryData) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Executes the search contained in the passed SqlQueryData, using it's encapsulated search string and bind variables.
     * @param queryData - Contains a search string and (optionally) bind variables.
     * @return List<String> A list of strings satisfying the passed search.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     * @implNote This method is used within searches which have _include or _revinclude parameters
     *           in order to return a list of Reference values (e.g. {@code "Patient/<UUID>"})
     *           to use for filtering the list of resources to be included with the response.
     */
    List<String> searchStringValues(SqlQueryData queryData) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;


    /**
     * Executes the passed fully-formed SQL Select statement and returns the results
     * If no matching resources are found, an empty collection is returned.
     * @param sqlSelect - A fully formed SQL select statement.
     * @return List<Resource> - A List of Resources that satisfy the passed SQL query string.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    List<Resource> search(String sqlSelect)
            throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * This method supports the execution of a specialized query designed to return Resource ids, based on the contents
     * of the passed select statement.
     * Note that the first column to be selected MUST be the Resource.id column.
     * @param sqlSelect - A select for Resource ids.
     * @return - A List of resource ids that satisfy the passed SQL query.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    List<Long> searchForIds(SqlQueryData  queryData) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Searches for Resources that contain one of the passed ids.
     * @param resourceType - The type of the FHIR Resource
     * @param resourceIds - A List of resource ids.
     * @return List<Resource> - A List of resources matching the the passed list of ids.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    List<Resource> searchByIds(String resourceType, List<Long> resourceIds) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Executes a count query based on the data contained in the passed SqlQueryData, using it's encapsulated search string and bind variables.
     * @param queryData - Contains a search string and (optionally) bind variables.
     * @return int A count of FHIR Resources satisfying the passed search.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    int searchCount(SqlQueryData queryData) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Executes the passed fully-formed SQL Select COUNT statement and returns the integer count.
     *
     * @param sqlSelect - A fully formed SQL select count statement.
     * @return int - The count of resources that fulfill the passed SQL select statement.
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    int searchCount(String sqlSelectCount) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException;

    /**
     * Sets the current persistence context
     * @param context
     */
    void setPersistenceContext(FHIRPersistenceContext context);

    /**
     * Reads all rows in the resource_types table and returns the data as a Map
     * @return Map<String, Integer> - A map containing key=parameter-name, value=parameter-name-id
     * @throws FHIRPersistenceDBConnectException
     * @throws FHIRPersistenceDataAccessException
     */
    Map<String,Integer> readAllResourceTypeNames() throws FHIRPersistenceDBConnectException, FHIRPersistenceDataAccessException;

    /**
     * Reads the id associated with the name of the passed Resource type from the Resource_Types table. If the id for the passed name is not present
     * in the database, an id is generated, persisted, and returned.
     * @param String A valid FHIR resource type.
     * @return Integer - the id associated with the name of the passed Resource type.
     * @throws FHIRPersistenceDBConnectException
     * @throws FHIRPersistenceDataAccessException
     */
    Integer readResourceTypeId(String parameterName) throws FHIRPersistenceDBConnectException, FHIRPersistenceDataAccessException;

    /**
     * Adds a resource type / resource id pair to a candidate collection for population into the ResourceTypesCache.
     * This pair must be present as a row in the FHIR DB RESOURCE_TYPES table.
     * @param resourceType A valid FHIR resource type.
     * @param resourceTypeId The corresponding id for the resource type.
     * @throws FHIRPersistenceException
     */
    void addResourceTypeCacheCandidate(String resourceType, Integer resourceTypeId) throws FHIRPersistenceException;

    /**
     * Inserts the passed Resource DTO and its associated search parameters to the appropriate FHIR resource tables.
     * After insert, the generated primary key is acquired and set in the Resource object.
     * @param resource A Resource Data Transfer Object
     * @param parameters A collection of search parameters to be persisted along with the passed Resource
     * @param parameterDao The Parameter DAO
     * @return Resource The Resource DTO
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     * @throws FHIRPersistenceVersionIdMismatchException
     * @throws FHIRPersistenceException
     */
    Resource insert(Resource resource, List<ExtractedParameterValue> parameters, ParameterDAO parameterDao)
            throws FHIRPersistenceException;

}
