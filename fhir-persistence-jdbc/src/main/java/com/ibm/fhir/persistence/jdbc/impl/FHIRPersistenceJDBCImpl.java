/*
 * (C) Copyright IBM Corp. 2017, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.persistence.jdbc.impl;

import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_CODE_SYSTEMS_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_PARAMETER_NAMES_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_RESOURCE_TYPES_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_UPDATE_CREATE_ENABLED;
import static com.ibm.fhir.model.type.String.string;
import static com.ibm.fhir.persistence.jdbc.JDBCConstants.MAX_NUM_OF_COMPOSITE_COMPONENTS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.naming.InitialContext;
import javax.transaction.Status;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;

import com.ibm.fhir.config.FHIRConfiguration;
import com.ibm.fhir.config.PropertyGroup;
import com.ibm.fhir.core.FHIRUtilities;
import com.ibm.fhir.core.context.FHIRPagingContext;
import com.ibm.fhir.database.utils.api.IConnectionProvider;
import com.ibm.fhir.exception.FHIRException;
import com.ibm.fhir.model.format.Format;
import com.ibm.fhir.model.generator.FHIRGenerator;
import com.ibm.fhir.model.parser.FHIRJsonParser;
import com.ibm.fhir.model.parser.FHIRParser;
import com.ibm.fhir.model.resource.OperationOutcome;
import com.ibm.fhir.model.resource.Resource;
import com.ibm.fhir.model.resource.SearchParameter;
import com.ibm.fhir.model.resource.SearchParameter.Component;
import com.ibm.fhir.model.type.CodeableConcept;
import com.ibm.fhir.model.type.Element;
import com.ibm.fhir.model.type.Id;
import com.ibm.fhir.model.type.Instant;
import com.ibm.fhir.model.type.Meta;
import com.ibm.fhir.model.type.code.IssueSeverity;
import com.ibm.fhir.model.type.code.IssueType;
import com.ibm.fhir.model.type.code.SearchParamType;
import com.ibm.fhir.model.util.FHIRUtil;
import com.ibm.fhir.model.util.JsonSupport;
import com.ibm.fhir.model.visitor.Visitable;
import com.ibm.fhir.path.FHIRPathNode;
import com.ibm.fhir.path.FHIRPathSystemValue;
import com.ibm.fhir.path.evaluator.FHIRPathEvaluator;
import com.ibm.fhir.path.evaluator.FHIRPathEvaluator.EvaluationContext;
import com.ibm.fhir.persistence.FHIRPersistence;
import com.ibm.fhir.persistence.FHIRPersistenceTransaction;
import com.ibm.fhir.persistence.MultiResourceResult;
import com.ibm.fhir.persistence.SingleResourceResult;
import com.ibm.fhir.persistence.context.FHIRHistoryContext;
import com.ibm.fhir.persistence.context.FHIRPersistenceContext;
import com.ibm.fhir.persistence.exception.FHIRPersistenceException;
import com.ibm.fhir.persistence.exception.FHIRPersistenceResourceDeletedException;
import com.ibm.fhir.persistence.exception.FHIRPersistenceResourceNotFoundException;
import com.ibm.fhir.persistence.jdbc.JDBCConstants;
import com.ibm.fhir.persistence.jdbc.dao.api.FHIRDbDAO;
import com.ibm.fhir.persistence.jdbc.dao.api.ParameterDAO;
import com.ibm.fhir.persistence.jdbc.dao.api.ResourceDAO;
import com.ibm.fhir.persistence.jdbc.dao.impl.FHIRDbDAOImpl;
import com.ibm.fhir.persistence.jdbc.dao.impl.ParameterDAOImpl;
import com.ibm.fhir.persistence.jdbc.dao.impl.ResourceDAOImpl;
import com.ibm.fhir.persistence.jdbc.dto.CompositeParmVal;
import com.ibm.fhir.persistence.jdbc.dto.DateParmVal;
import com.ibm.fhir.persistence.jdbc.dto.ExtractedParameterValue;
import com.ibm.fhir.persistence.jdbc.dto.NumberParmVal;
import com.ibm.fhir.persistence.jdbc.dto.QuantityParmVal;
import com.ibm.fhir.persistence.jdbc.dto.StringParmVal;
import com.ibm.fhir.persistence.jdbc.dto.TokenParmVal;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDBConnectException;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDataAccessException;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceFKVException;
import com.ibm.fhir.persistence.jdbc.util.CodeSystemsCache;
import com.ibm.fhir.persistence.jdbc.util.JDBCParameterBuildingVisitor;
import com.ibm.fhir.persistence.jdbc.util.JDBCQueryBuilder;
import com.ibm.fhir.persistence.jdbc.util.ParameterNamesCache;
import com.ibm.fhir.persistence.jdbc.util.ResourceTypesCache;
import com.ibm.fhir.persistence.jdbc.util.SqlQueryData;
import com.ibm.fhir.persistence.util.FHIRPersistenceUtil;
import com.ibm.fhir.search.SearchConstants;
import com.ibm.fhir.search.SummaryValueSet;
import com.ibm.fhir.search.context.FHIRSearchContext;
import com.ibm.fhir.search.date.DateTimeHandler;
import com.ibm.fhir.search.parameters.QueryParameter;
import com.ibm.fhir.search.util.SearchUtil;

/**
 * The JDBC implementation of the FHIRPersistence interface,
 * providing implementations for CRUD APIs and search.
 */
public class FHIRPersistenceJDBCImpl implements FHIRPersistence, FHIRPersistenceTransaction {
    private static final String CLASSNAME = FHIRPersistenceJDBCImpl.class.getName();
    private static final Logger log = Logger.getLogger(CLASSNAME);

    protected static final String TXN_JNDI_NAME = "java:comp/UserTransaction";
    public static final String TRX_SYNCH_REG_JNDI_NAME = "java:comp/TransactionSynchronizationRegistry";

    private FHIRDbDAO baseDao;
    private ResourceDAO resourceDao;
    private ParameterDAO parameterDao;
    private TransactionSynchronizationRegistry trxSynchRegistry;

    protected Connection sharedConnection = null;
    protected UserTransaction userTransaction = null;
    protected Boolean updateCreateEnabled = null;

    // only used outside a web container
    private Connection managedConnection;

    /**
     * Constructor for use when running as web application in WLP.
     * @throws Exception
     */
    public FHIRPersistenceJDBCImpl() throws Exception {
        final String METHODNAME = "FHIRPersistenceJDBCImpl()";
        log.entering(CLASSNAME, METHODNAME);

        PropertyGroup fhirConfig = FHIRConfiguration.getInstance().loadConfiguration();
        this.updateCreateEnabled = fhirConfig.getBooleanProperty(PROPERTY_UPDATE_CREATE_ENABLED, Boolean.TRUE);
        this.userTransaction = retrieveUserTransaction(TXN_JNDI_NAME);

        ParameterNamesCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_PARAMETER_NAMES_CACHE,
                                       Boolean.TRUE));
        CodeSystemsCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_CODE_SYSTEMS_CACHE,
                                    Boolean.TRUE));
        ResourceTypesCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_RESOURCE_TYPES_CACHE,
                                      Boolean.TRUE));
        this.resourceDao = new ResourceDAOImpl(this.getTrxSynchRegistry());
        this.parameterDao = new ParameterDAOImpl(this.getTrxSynchRegistry());

        log.exiting(CLASSNAME, METHODNAME);
    }

    /**
     * Constructor for use when running standalone, outside of any web container.
     * @throws Exception
     */
    public FHIRPersistenceJDBCImpl(Properties configProps) throws Exception {
        final String METHODNAME = "FHIRPersistenceJDBCImpl(Properties)";
        log.entering(CLASSNAME, METHODNAME);

        this.updateCreateEnabled = Boolean.parseBoolean(configProps.getProperty("updateCreateEnabled"));

        FHIRDbDAO dao = new FHIRDbDAOImpl(configProps);

        this.setBaseDao(dao);
        this.setManagedConnection(this.getBaseDao().getConnection());
        this.resourceDao = new ResourceDAOImpl(this.getManagedConnection());
        this.parameterDao = new ParameterDAOImpl(this.getManagedConnection());

        log.exiting(CLASSNAME, METHODNAME);
    }

    /**
     * Constructor for use when running standalone, outside of any web container.
     * @throws Exception
     */
    public FHIRPersistenceJDBCImpl(Properties configProps, IConnectionProvider cp) throws Exception {
        final String METHODNAME = "FHIRPersistenceJDBCImpl(Properties, IConnectionProvider)";
        log.entering(CLASSNAME, METHODNAME);

        this.updateCreateEnabled = Boolean.parseBoolean(configProps.getProperty("updateCreateEnabled"));

        FHIRDbDAO dao = new FHIRDbDAOImpl(cp.getConnection());

        this.setBaseDao(dao);
        this.setManagedConnection(this.getBaseDao().getConnection());
        this.resourceDao = new ResourceDAOImpl(this.getManagedConnection());
        this.parameterDao = new ParameterDAOImpl(this.getManagedConnection());

        log.exiting(CLASSNAME, METHODNAME);
    }

    @Override
    public <T extends Resource> SingleResourceResult<T> create(FHIRPersistenceContext context, T resource) throws FHIRPersistenceException  {
        final String METHODNAME = "create";
        log.entering(CLASSNAME, METHODNAME);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        String logicalId;

        // We need to update the meta in the resource, so we need a modifiable version
        Resource.Builder resultBuilder = resource.toBuilder();


        try {
            // This create() operation is only called by a REST create. If the given resource
            // contains an id, then for R4 we need to ignore it and replace it with our
            // system-generated value. For the update-or-create scenario, see update().
            // Default version is 1 for a brand new FHIR Resource.
            int newVersionNumber = 1;
            logicalId = UUID.randomUUID().toString();
            if (log.isLoggable(Level.FINE)) {
                log.fine("Creating new FHIR Resource of type '" + resource.getClass().getSimpleName() + "'");
            }

            // Set the resource id and meta fields.
            Instant lastUpdated = Instant.now(ZoneOffset.UTC);
            resultBuilder.id(logicalId);
            Meta meta = resource.getMeta();
            Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
            metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
            metaBuilder.lastUpdated(lastUpdated);
            resultBuilder.meta(metaBuilder.build());

            // rebuild the resource with updated meta
            @SuppressWarnings("unchecked")
            T updatedResource = (T) resultBuilder.build();

            // Create the new Resource DTO instance.
            com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
            resourceDTO.setLogicalId(logicalId);
            resourceDTO.setVersionId(newVersionNumber);
            Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
            resourceDTO.setLastUpdated(timestamp);
            resourceDTO.setResourceType(updatedResource.getClass().getSimpleName());

            // Serialize and compress the Resource
            GZIPOutputStream zipStream = new GZIPOutputStream(stream);
            FHIRGenerator.generator( Format.JSON, false).generate(updatedResource, zipStream);
            zipStream.finish();
            resourceDTO.setData(stream.toByteArray());
            zipStream.close();

            // Persist the Resource DTO.
            this.getResourceDao().setPersistenceContext(context);
            this.getResourceDao().insert(resourceDTO, this.extractSearchParameters(updatedResource, resourceDTO), this.parameterDao);
            if (log.isLoggable(Level.FINE)) {
                log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                            + ", version=" + resourceDTO.getVersionId());
            }

            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(updatedResource)
                    .build();

            return result;
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, "FK violation", e);
//            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a create operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
           log.exiting(CLASSNAME, METHODNAME);
        }
    }

    @Override
    public <T extends Resource> SingleResourceResult<T> update(FHIRPersistenceContext context, String logicalId, T resource)
            throws FHIRPersistenceException {
        final String METHODNAME = "update";
        log.entering(CLASSNAME, METHODNAME);

        Class<? extends Resource> resourceType = resource.getClass();
        com.ibm.fhir.persistence.jdbc.dto.Resource existingResourceDTO;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        // Resources are immutable, so we need a new builder to update it (since R4)
        Resource.Builder resultBuilder = resource.toBuilder();

        try {
            // Assume we have no existing resource.
            int existingVersion = 0;

            // Compute the new version # from the existing version #.

            // If the "previous resource" is set in the persistence event, then get the
            // existing version # from that.
            if (context.getPersistenceEvent() != null && context.getPersistenceEvent().isPrevFhirResourceSet()) {
                Resource existingResource = context.getPersistenceEvent().getPrevFhirResource();
                if (existingResource != null) {
                    log.fine("Using pre-fetched 'previous' resource.");
                    String version = existingResource.getMeta().getVersionId().getValue();
                    existingVersion = Integer.valueOf(version);
                }
            }

            // Otherwise, go ahead and read the resource from the datastore and get the
            // existing version # from it.
            else {
                log.fine("Fetching 'previous' resource for update.");
                existingResourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());
                if (existingResourceDTO != null) {
                    existingVersion = existingResourceDTO.getVersionId();
                }
            }

            // If this logical resource didn't exist and the "updateCreate" feature is not enabled,
            // then this is an error.
            if (existingVersion == 0 && !updateCreateEnabled) {
                String msg = "Resource '" + resourceType.getSimpleName() + "/" + logicalId + "' not found.";
                log.log(Level.SEVERE, msg);
                throw new FHIRPersistenceResourceNotFoundException(msg);
            }

            // Bump up the existing version # to get the new version.
            int newVersionNumber = existingVersion + 1;

            if (log.isLoggable(Level.FINE)) {
                if (existingVersion != 0) {
                    log.fine("Updating FHIR Resource '" + resource.getClass().getSimpleName() + "/" + logicalId + "', version=" + existingVersion);
                }
                log.fine("Storing new FHIR Resource '" + resource.getClass().getSimpleName() + "/" + logicalId + "', version=" + newVersionNumber);
            }

            Instant lastUpdated = Instant.now(ZoneOffset.UTC);

            // Set the resource id and meta fields.
//            resultBuilder.id(logicalId);
            Meta meta = resource.getMeta();
            Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
            metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
            metaBuilder.lastUpdated(lastUpdated);
            resultBuilder.meta(metaBuilder.build());

            @SuppressWarnings("unchecked")
            T updatedResource = (T) resultBuilder.build();

            // Create the new Resource DTO instance.
            com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
            resourceDTO.setLogicalId(logicalId);
            resourceDTO.setVersionId(newVersionNumber);
            Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
            resourceDTO.setLastUpdated(timestamp);
            resourceDTO.setResourceType(updatedResource.getClass().getSimpleName());

            // Serialize and compress the Resource
            GZIPOutputStream zipStream = new GZIPOutputStream(stream);
            FHIRGenerator.generator(Format.JSON, false).generate(updatedResource, zipStream);
            zipStream.finish();
            resourceDTO.setData(stream.toByteArray());
            zipStream.close();

            // Persist the Resource DTO.
            this.getResourceDao().setPersistenceContext(context);
            this.getResourceDao().insert(resourceDTO, this.extractSearchParameters(updatedResource, resourceDTO), this.parameterDao);
            if (log.isLoggable(Level.FINE)) {
                log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                            + ", version=" + resourceDTO.getVersionId());
            }

            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(updatedResource)
                    .build();

            return result;
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            // don't chain the exception to avoid leaking secrets
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing an update operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    @Override
    public MultiResourceResult<Resource> search(FHIRPersistenceContext context, Class<? extends Resource> resourceType)
            throws FHIRPersistenceException {
        final String METHODNAME = "search";
        log.entering(CLASSNAME, METHODNAME);

        List<Resource> resources = Collections.emptyList();
        MultiResourceResult.Builder<Resource> resultBuilder = new MultiResourceResult.Builder<>();
        FHIRSearchContext searchContext = context.getSearchContext();
        JDBCQueryBuilder queryBuilder;
        List<Long> sortedIdList;
        List<com.ibm.fhir.persistence.jdbc.dto.Resource> unsortedResultsList;
        int searchResultCount = 0;
        SqlQueryData countQuery;
        SqlQueryData query;

        try {
            checkModifiers(searchContext);
            queryBuilder = new JDBCQueryBuilder(this.getParameterDao(),
                                                this.getResourceDao());

            countQuery = queryBuilder.buildCountQuery(resourceType, searchContext);
            if (countQuery != null) {
                searchResultCount = this.getResourceDao().searchCount(countQuery);
                if (log.isLoggable(Level.FINE)) {
                    log.fine("searchResultCount = " + searchResultCount);
                }
                searchContext.setTotalCount(searchResultCount);

                List<OperationOutcome.Issue> issues = validatePagingContext(searchContext);
                if (!issues.isEmpty()) {
                    resultBuilder.outcome(OperationOutcome.builder()
                        .issue(issues)
                        .build());
                    if (!searchContext.isLenient()) {
                        return resultBuilder.success(false).build();
                    }
                }

                // For _summary=count or pageSize == 0, we return only the count
                if (searchResultCount > 0
                        && !SummaryValueSet.COUNT.equals(searchContext.getSummaryParameter())
                        && searchContext.getPageSize() > 0) {
                    query = queryBuilder.buildQuery(resourceType, searchContext);

                    List<String> elements = searchContext.getElementsParameters();

                    //Only consider _summary if _elements parameter is empty
                    if (elements == null && searchContext.hasSummaryParameter()) {
                        Set<String> summaryElements = null;
                        SummaryValueSet summary = searchContext.getSummaryParameter();

                        switch (summary) {
                        case TRUE:
                            summaryElements = JsonSupport.getSummaryElementNames(resourceType);
                            break;
                        case TEXT:
                            summaryElements = SearchUtil.getSummaryTextElementNames(resourceType);
                            break;
                        case DATA:
                            summaryElements = JsonSupport.getSummaryDataElementNames(resourceType);
                            break;
                        default:
                            break;
                        }

                        if (summaryElements != null) {
                            elements = new ArrayList<>();
                            elements.addAll(summaryElements);
                        }
                    }

                    if (searchContext.hasSortParameters()) {
                        // Sorting results of a system-level search is limited, and has a different logic path
                        // than other sorted searches.
                        if (resourceType.equals(Resource.class)) {
                           resources = this.convertResourceDTOList(this.resourceDao.search(query), resourceType, elements);
                        }
                        else {
                            sortedIdList = this.resourceDao.searchForIds(query);
                            resources = this.buildSortedFhirResources(context, resourceType, sortedIdList, elements);
                        }
                    }
                    else {
                        unsortedResultsList = this.getResourceDao().search(query);
                        resources = this.convertResourceDTOList(unsortedResultsList, resourceType, elements);
                    }
                }
            }

            return resultBuilder
                    .success(true)
                    .resource(resources)
                    .build();
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a search operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /**
     * @throws FHIRPersistenceException if the search context contains one or more unsupported modifiers
     */
    private void checkModifiers(FHIRSearchContext searchContext) throws FHIRPersistenceException {
        for (QueryParameter param : searchContext.getSearchParameters()) {
            do {
                if(param.getModifier() != null &&
                        !JDBCConstants.supportedModifiersMap.get(param.getType()).contains(param.getModifier())) {
                    throw new FHIRPersistenceException("Found unsupported modifier '" + param.getModifier() + "'"
                            + " for search parameter '" + param.getCode() + "' of type " + param.getType());
                }
                param = param.getNextParameter();
            } while (param != null);
        }
    }

    private ParameterDAO getParameterDao() {
        return parameterDao;
    }

    private ResourceDAO getResourceDao() {
        return resourceDao;
    }

    @Override
    public <T extends Resource> SingleResourceResult<T> delete(FHIRPersistenceContext context, Class<T> resourceType, String logicalId) throws FHIRPersistenceException {
        final String METHODNAME = "delete";
        log.entering(CLASSNAME, METHODNAME);


        com.ibm.fhir.persistence.jdbc.dto.Resource existingResourceDTO = null;
        T existingResource = null;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        Resource.Builder resourceBuilder;

        try {
            existingResourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());

            if (existingResourceDTO != null) {
                if (existingResourceDTO.isDeleted()) {
                    existingResource = this.convertResourceDTO(existingResourceDTO, resourceType, null);
                    resourceBuilder = existingResource.toBuilder();

                    SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                            .success(true)
                            .resource(existingResource)
                            .build();

                    return result;
                }
                else {
                    existingResource = this.convertResourceDTO(existingResourceDTO, resourceType, null);

                    // Resources are immutable, so we need a new builder to update it (since R4)
                    resourceBuilder = existingResource.toBuilder();

                    int newVersionNumber = existingResourceDTO.getVersionId() + 1;
                    Instant lastUpdated = Instant.now(ZoneOffset.UTC);

                    // Update the soft-delete resource to reflect the new version and lastUpdated values.
                    Meta meta = existingResource.getMeta();
                    Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
                    metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
                    metaBuilder.lastUpdated(lastUpdated);
                    resourceBuilder.meta(metaBuilder.build());


                    @SuppressWarnings("unchecked")
                    T updatedResource = (T) resourceBuilder.build();

                    // Create a new Resource DTO instance to represent the deleted version.
                    com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
                    resourceDTO.setLogicalId(logicalId);
                    resourceDTO.setVersionId(newVersionNumber);

                    // Serialize and compress the Resource
                    GZIPOutputStream zipStream = new GZIPOutputStream(stream);
                    FHIRGenerator.generator(Format.JSON, false).generate(updatedResource, zipStream);
                    zipStream.finish();
                    resourceDTO.setData(stream.toByteArray());
                    zipStream.close();

                    Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
                    resourceDTO.setLastUpdated(timestamp);
                    resourceDTO.setResourceType(resourceType.getSimpleName());
                    resourceDTO.setDeleted(true);

                    // Persist the logically deleted Resource DTO.
                    this.getResourceDao().setPersistenceContext(context);
                    this.getResourceDao().insert(resourceDTO, null, null);

                    if (log.isLoggable(Level.FINE)) {
                        log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                                    + ", version=" + resourceDTO.getVersionId());
                    }

                    SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                            .success(true)
                            .resource(updatedResource)
                            .build();

                    return result;
                }
            }
            else {
                throw new FHIRPersistenceResourceNotFoundException("resource does not exist: " + resourceType.getSimpleName() + ":" + logicalId);
            }
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a delete operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    @Override
    public <T extends Resource> SingleResourceResult<T> read(FHIRPersistenceContext context, Class<T> resourceType, String logicalId)
                            throws FHIRPersistenceException, FHIRPersistenceResourceDeletedException {
        final String METHODNAME = "read";
        log.entering(CLASSNAME, METHODNAME);

        T resource = null;
        com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = null;

        FHIRSearchContext searchContext = context.getSearchContext();
        List<String> elements = null;
        //Check if _summary is required
        if (searchContext != null && searchContext.hasSummaryParameter()) {
            Set<String> summaryElements = null;
            SummaryValueSet summary = searchContext.getSummaryParameter();

            switch (summary) {
            case TRUE:
                summaryElements = JsonSupport.getSummaryElementNames(resourceType);
                break;
            case TEXT:
                summaryElements = SearchUtil.getSummaryTextElementNames(resourceType);
                break;
            case DATA:
                summaryElements = JsonSupport.getSummaryDataElementNames(resourceType);
                break;
            default:
                break;
            }

            if (summaryElements != null) {
                elements = new ArrayList<String>();
                elements.addAll(summaryElements);
            }
        }

        try {
            resourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());
            if (resourceDTO != null && resourceDTO.isDeleted() && !context.includeDeleted()) {
                throw new FHIRPersistenceResourceDeletedException("Resource '" +
                        resourceType.getSimpleName() + "/" + logicalId + "' is deleted.");
            }
            resource = this.convertResourceDTO(resourceDTO, resourceType, elements);

            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(resource)
                    .build();

            return result;
        }
        catch(FHIRPersistenceResourceDeletedException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a read operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    @Override
    public <T extends Resource> MultiResourceResult<T> history(FHIRPersistenceContext context, Class<T> resourceType,
            String logicalId) throws FHIRPersistenceException {
        final String METHODNAME = "history";
        log.entering(CLASSNAME, METHODNAME);

        List<T> resources = new ArrayList<>();
        MultiResourceResult.Builder<T> resultBuilder = new MultiResourceResult.Builder<>();
        List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList;
        Map<String,List<Integer>> deletedResourceVersions = new HashMap<>();
        FHIRHistoryContext historyContext;
        int resourceCount;
        Instant since;
        Timestamp fromDateTime = null;
        int offset;

        try {
            historyContext = context.getHistoryContext();
            historyContext.setDeletedResources(deletedResourceVersions);
            since = historyContext.getSince();
            if (since != null) {
                fromDateTime = FHIRUtilities.convertToTimestamp(since.getValue());
            }

            resourceCount = this.getResourceDao().historyCount(resourceType.getSimpleName(), logicalId, fromDateTime);
            historyContext.setTotalCount(resourceCount);

            List<OperationOutcome.Issue> issues = validatePagingContext(historyContext);

            if (!issues.isEmpty()) {
                resultBuilder.outcome(OperationOutcome.builder()
                    .issue(issues)
                    .build());
                if (!historyContext.isLenient()) {
                    return resultBuilder.success(false).build();
                }
            }

            if (resourceCount > 0) {
                offset = (historyContext.getPageNumber() - 1) * historyContext.getPageSize();
                resourceDTOList = this.getResourceDao().history(resourceType.getSimpleName(), logicalId, fromDateTime, offset, historyContext.getPageSize());
                for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                    if (resourceDTO.isDeleted()) {
                        deletedResourceVersions.putIfAbsent(logicalId, new ArrayList<Integer>());
                        deletedResourceVersions.get(logicalId).add(resourceDTO.getVersionId());
                    }
                }
                log.log(Level.FINE, "deletedResourceVersions=" + deletedResourceVersions);
                resources = this.convertResourceDTOList(resourceDTOList, resourceType);
            }

            return resultBuilder
                    .success(true)
                    .resource(resources)
                    .build();
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a history operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /**
     * Validate pageSize and pageNumber in the FHIRPagingContext instance and update
     * paging context parameters accordingly.
     *
     * @param pagingContext
     *     the FHIRPagingContext instance (FHIRSearchContext or FHIRHistoryContext)
     * @return
     *     a list of operation outcome issues if the paging context has invalid parameters
     */
    private List<OperationOutcome.Issue> validatePagingContext(FHIRPagingContext pagingContext) {
        List<OperationOutcome.Issue> issues = new ArrayList<>();

        int pageSize = pagingContext.getPageSize();
        if (pageSize < 0) {
            issues.add(OperationOutcome.Issue.builder()
                .severity(pagingContext.isLenient() ? IssueSeverity.WARNING : IssueSeverity.ERROR)
                .code(IssueType.INVALID)
                .details(CodeableConcept.builder()
                    .text(string("Invalid page size: " + pageSize))
                    .build())
                .build());
            pagingContext.setPageSize(10);
        }

        int lastPageNumber = Math.max(((pagingContext.getTotalCount() + pageSize - 1) / pageSize), 1);
        pagingContext.setLastPageNumber(lastPageNumber);

        int pageNumber = pagingContext.getPageNumber();
        if (pageNumber < 1) {
            issues.add(OperationOutcome.Issue.builder()
                .severity(pagingContext.isLenient() ? IssueSeverity.WARNING : IssueSeverity.ERROR)
                .code(IssueType.INVALID)
                .details(CodeableConcept.builder()
                    .text(string("Invalid page number: " + pageNumber))
                    .build())
                .build());
            pagingContext.setPageNumber(1);
        } else if (pageNumber > lastPageNumber) {
            issues.add(OperationOutcome.Issue.builder()
                .severity(pagingContext.isLenient() ? IssueSeverity.WARNING : IssueSeverity.ERROR)
                .code(IssueType.INVALID)
                .details(CodeableConcept.builder()
                    .text(string("Specified page number: " + pageNumber + " is greater than last page number: " + lastPageNumber))
                    .build())
                .build());
            pagingContext.setPageNumber(lastPageNumber);
        }

        return issues;
    }


    @Override
    public <T extends Resource> SingleResourceResult<T> vread(FHIRPersistenceContext context, Class<T> resourceType, String logicalId, String versionId)
                        throws FHIRPersistenceException {
        final String METHODNAME = "vread";
        log.entering(CLASSNAME, METHODNAME);

        T resource = null;
        com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = null;
        int version;

        try {
            version = Integer.parseInt(versionId);
            resourceDTO = this.getResourceDao().versionRead(logicalId, resourceType.getSimpleName(), version);
            if (resourceDTO != null && resourceDTO.isDeleted()) {
                throw new FHIRPersistenceResourceDeletedException("Resource '" +
                        resourceType.getSimpleName() + "/" + logicalId + "' version " + versionId + " is deleted.");
            }
            resource = this.convertResourceDTO(resourceDTO, resourceType, null);

            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(resource)
                    .build();

            return result;
        }
        catch(FHIRPersistenceResourceDeletedException e) {
            throw e;
        }
        catch (NumberFormatException e) {
            throw new FHIRPersistenceException("Invalid version id specified for vread operation: " + versionId);
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a version read operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /**
     * This method takes the passed list of sorted Resource ids, acquires the Resource corresponding to each id, and returns those Resources in a List,
     * sorted according to the input sorted ids.
     * @param context - The FHIR persistence context for the current request.
     * @param resourceType - The type of Resource that each id in the passed list represents.
     * @param sortedIdList - A list of Resource ids representing the proper sort order for the list of Resources to be returned.
     * @param elements - An optional list of element names to include in the resources. If null, filtering will be skipped.
     * @return List<Resource> - A list of Resources of the passed resourceType, sorted according the order of ids in the passed sortedIdList.
     * @throws FHIRPersistenceException
     * @throws IOException
     */
    protected List<Resource> buildSortedFhirResources(FHIRPersistenceContext context, Class<? extends Resource> resourceType, List<Long> sortedIdList,
                                                      List<String> elements)
                            throws FHIRException, FHIRPersistenceException, IOException {
        final String METHOD_NAME = "buildFhirResource";
        log.entering(this.getClass().getName(), METHOD_NAME);

        long resourceId;
        Resource[] sortedFhirResources = new Resource[sortedIdList.size()];
        Resource fhirResource;
        int sortIndex;
        List<Resource> sortedResourceList = new ArrayList<>();
        List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList;
        Map<Long,Integer> idPositionMap = new HashMap<>();

        // This loop builds a Map where key=resourceId, and value=its proper position in the returned sorted collection.
        for(int i = 0; i < sortedIdList.size(); i++) {
            resourceId = sortedIdList.get(i);
            idPositionMap.put(new Long(resourceId), new Integer(i));
        }

        resourceDTOList = this.getResourceDTOs(resourceType, sortedIdList);


        // Convert the returned JPA Resources to FHIR Resources, and store each FHIRResource in its proper position
        // in the returned sorted resource list.
        for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
            fhirResource = this.convertResourceDTO(resourceDTO, resourceType, elements);
            if (fhirResource != null) {
                sortIndex = idPositionMap.get(resourceDTO.getId());
                sortedFhirResources[sortIndex] = fhirResource;
            }
        }

        for (int i = 0; i <sortedFhirResources.length; i++) {
            if (sortedFhirResources[i] != null) {
                sortedResourceList.add(sortedFhirResources[i]);
            }
        }
        log.exiting(this.getClass().getName(), METHOD_NAME);
        return sortedResourceList;
    }

    /**
     * Returns a List of Resource DTOs corresponding to the passed list of Resource IDs.
     * @param resourceType The type of resource being queried.
     * @param sortedIdList A sorted list of Resource IDs.
     * @return List - A list of ResourceDTOs
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    private List<com.ibm.fhir.persistence.jdbc.dto.Resource> getResourceDTOs(
            Class<? extends Resource> resourceType, List<Long> sortedIdList) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException {

        return this.getResourceDao().searchByIds(resourceType.getSimpleName(), sortedIdList);
    }

    /**
     * Converts the passed Resource Data Transfer Object collection to a collection of FHIR Resource objects.
     * @param resourceDTOList
     * @param resourceType
     * @return
     * @throws FHIRException
     * @throws IOException
     */
    protected List<Resource> convertResourceDTOList(List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList,
            Class<? extends Resource> resourceType, List<String> elements) throws FHIRException, IOException {
        final String METHODNAME = "convertResourceDTO List";
        log.entering(CLASSNAME, METHODNAME);

        List<Resource> resources = new ArrayList<>();
        try {
            for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                Resource existingResource = this.convertResourceDTO(resourceDTO, resourceType, elements);
                if (resourceDTO.isDeleted()) {
                    Resource deletedResourceMarker = FHIRPersistenceUtil.createDeletedResourceMarker(existingResource);
                    resources.add(deletedResourceMarker);
                } else {
                    resources.add(existingResource);
                }
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return resources;
    }

   /**
     * Calls some cache analysis methods and aggregates the output into a single String.
     * @return
     */
    private String performCacheDiagnostics() {

        StringBuffer diags = new StringBuffer();
        diags.append(ParameterNamesCache.dumpCacheContents()).append(ParameterNamesCache.reportCacheDiscrepancies(this.parameterDao));
        diags.append(CodeSystemsCache.dumpCacheContents()).append(CodeSystemsCache.reportCacheDiscrepancies(this.parameterDao));
        diags.append(ResourceTypesCache.dumpCacheContents()).append(ResourceTypesCache.reportCacheDiscrepancies(this.resourceDao));

        return diags.toString();
    }

    /**
     * Looks up and returns an instance of TransactionSynchronizationRegistry, which is used in support of writing committed
     * data to JDBC PL in-memory caches.
     * @return TransactionSynchronizationRegistry
     * @throws FHIRPersistenceException
     */
    private TransactionSynchronizationRegistry getTrxSynchRegistry() throws FHIRPersistenceException {

        InitialContext ctxt;

        if (this.trxSynchRegistry == null) {
            try {
                ctxt = new InitialContext();
                this.trxSynchRegistry = (TransactionSynchronizationRegistry) ctxt.lookup(TRX_SYNCH_REG_JNDI_NAME);
            }
            catch(Throwable e) {
                FHIRPersistenceException fx = new FHIRPersistenceException("Failed to acquire TrxSynchRegistry service");
                log.log(Level.SEVERE, fx.getMessage(), e);
                throw fx;
            }
        }

        return this.trxSynchRegistry;
    }

    /**
     * Extracts search parameters for the passed FHIR Resource.
     * @param fhirResource - Some FHIR Resource
     * @param resourceDTO - A Resource DTO representation of the passed FHIR Resource.
     * @throws Exception
     */
    private List<ExtractedParameterValue> extractSearchParameters(Resource fhirResource, com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO)
                 throws Exception {
        final String METHODNAME = "extractSearchParameters";
        log.entering(CLASSNAME, METHODNAME);

        Map<SearchParameter, List<FHIRPathNode>> map;
        String code;
        String type;
        String expression;

        List<ExtractedParameterValue> allParameters = new ArrayList<>();

        try {
            map = SearchUtil.extractParameterValues(fhirResource);

            for (Entry<SearchParameter, List<FHIRPathNode>> entry : map.entrySet()) {
                SearchParameter sp = entry.getKey();
                code = sp.getCode().getValue();
                type = sp.getType().getValue();
                expression = sp.getExpression().getValue();

                if (log.isLoggable(Level.FINE)) {
                    log.fine("Processing SearchParameter code: " + code + ", type: " + type + ", expression: " + expression);
                }

                List<FHIRPathNode> values = entry.getValue();

                if (SearchParamType.COMPOSITE.equals(sp.getType())) {
                    List<Component> components = sp.getComponent();
                    if (components.size() > MAX_NUM_OF_COMPOSITE_COMPONENTS) {
                        throw new UnsupportedOperationException(String.format("Found %d components for search parameter '%s', "
                                + "but this persistence layer can only support composites of %d or fewer components",
                                components.size(), code, MAX_NUM_OF_COMPOSITE_COMPONENTS));
                    }
                    FHIRPathEvaluator evaluator = FHIRPathEvaluator.evaluator();

                    for (FHIRPathNode value : values) {
                        Visitable fhirNode;
                        EvaluationContext context;
                        if (value.isResourceNode()) {
                            fhirNode = value.asResourceNode().resource();
                            context = new EvaluationContext((Resource) fhirNode);
                        } else if (value.isElementNode()) {
                            fhirNode = value.asElementNode().element();
                            context = new EvaluationContext((Element) fhirNode);
                        } else {
                            throw new IllegalStateException("Composite parameter expression must select one or more FHIR elements");
                        }

                        CompositeParmVal p = new CompositeParmVal();
                        p.setName(code);
                        p.setResourceType(fhirResource.getClass().getSimpleName());

                        for (int i = 0; i < components.size(); i++) {
                            Component component = components.get(i);
                            Collection<FHIRPathNode> nodes = evaluator.evaluate(context, component.getExpression().getValue());
                            if (nodes.isEmpty()){
                                if (log.isLoggable(Level.FINER)) {
                                    log.finer("Component expression '" + component.getExpression().getValue() + "' resulted in 0 nodes; "
                                            + "skipping composite parameter '" + code + "'.");
                                }
                                continue;
                            }

                            // Alternative: consider pulling the search parameter from the FHIRRegistry instead so we can use versioned references.
                            // Of course, that would require adding extension-search-params to the Registry which would require the Registry to be tenant-aware.
//                            SearchParameter compSP = FHIRRegistry.getInstance().getResource(component.getDefinition().getValue(), SearchParameter.class);
                            SearchParameter compSP = SearchUtil.getSearchParameter(p.getResourceType(), component.getDefinition());
                            JDBCParameterBuildingVisitor parameterBuilder = new JDBCParameterBuildingVisitor(compSP);
                            FHIRPathNode node = nodes.iterator().next();
                            if (nodes.size() > 1 ) {
                                // TODO: support component expressions that result in multiple nodes
                                // On the current schema, this means creating a different CompositeParmValue for each ordered set of component values.
                                // For example, if a composite has two components and each one's expression results in two nodes
                                // ([Code1,Code2] and [Quantity1,Quantity2]) and each node results in a single ExtractedParameterValue,
                                // then we need to generate CompositeParmVal objects for [Code1,Quantity1], [Code1,Quantity2],
                                // [Code2,Quantity1], and [Code2,Quantity2].
                                // Assumption: this should be rare.
                                log.fine("Component expression '" + component.getExpression().getValue() + "' resulted in multiple nodes; "
                                        + "proceeding with randomly chosen node '" + node.path() + "' for search parameter '" + code + "'.");
                            }

                            try {
                                if (node.isElementNode()) {
                                    // parameterBuilder aggregates the results for later retrieval
                                    node.asElementNode().element().accept(parameterBuilder);
                                    // retrieve the list of parameters built from all the FHIRPathElementNode values
                                    List<ExtractedParameterValue> parameters = parameterBuilder.getResult();
                                    if (parameters.isEmpty()){
                                        log.fine("Selected element '" + node.path() + "' resulted in 0 extracted parameter values; "
                                                + "skipping composite parameter '" + code + "'.");
                                        continue;
                                    }

                                    if (parameters.size() > 1) {
                                        // TODO: support component searchParms that lead to multiple ExtractedParameterValues
                                        // On the current schema, this means creating a different CompositeParmValue for each ordered set of component values.
                                        // For example:
                                        // If a composite has two components and each results in two extracted parameters ([A,B] and [1,2] respectively)
                                        // then we need to generate CompositeParmVal objects for [A,1], [A,2], [B,1], and [B,2]
                                        // Assumption: this should only be common for Quantity search parameters with both a coded unit and a display unit,
                                        // and in these cases, the coded unit is almost always the preferred value for search.
                                        log.fine("Selected element '" + node.path() + "' resulted in multiple extracted parameter values; "
                                                + "proceeding with the first extracted value for composite parameter '" + code + "'.");
                                    }
                                    ExtractedParameterValue componentParam = parameters.get(0);
                                    // override the component parameter name with the composite parameter name
                                    componentParam.setName(code);
                                    componentParam.setResourceType(p.getResourceType());
                                    componentParam.setBase(p.getBase());
                                    p.addComponent(componentParam);
                                } else if (node.isSystemValue()){
                                    ExtractedParameterValue primitiveParam = processPrimitiveValue(node.asSystemValue());
                                    primitiveParam.setName(code);
                                    primitiveParam.setResourceType(fhirResource.getClass().getSimpleName());

                                    if (log.isLoggable(Level.FINE)) {
                                        log.fine("Extracted Parameter '" + p.getName() + "' from Resource.");
                                    }
                                    p.addComponent(primitiveParam);
                                } else {
                                    // log and continue
                                    if (log.isLoggable(Level.FINE)) {
                                        log.fine("Unable to extract value from '" + value.path() +
                                                "'; search parameter value extraction can only be performed on Elements and primitive values.");
                                    }
                                    // TODO: return this as a OperationOutcomeIssue with severity of WARNING
                                    continue;
                                }
                            } catch (IllegalArgumentException e) {
                                // log and continue with the other parameters
                                StringBuilder msg = new StringBuilder("Skipping search parameter '" + code + "'");
                                if (sp.getId() != null) {
                                    msg.append(" with id '" + sp.getId() + "'");
                                }
                                msg.append(" for resource type " + fhirResource.getClass().getSimpleName());
                                // just use the message...no need for the whole stack trace
                                msg.append(" due to \n" + e.getMessage());
                                log.log(Level.INFO, msg.toString());
                                // TODO: add an issue to the OperationOutcome in the return object
                            }
                        }
                        if (components.size() == p.getComponent().size()) {
                            // only add the parameter if all of the components are present and accounted for
                            allParameters.add(p);
                        }
                    }
                } else {
                    JDBCParameterBuildingVisitor parameterBuilder = new JDBCParameterBuildingVisitor(sp);

                    for (FHIRPathNode value : values) {

                        try {
                            if (value.isElementNode()) {
                                // parameterBuilder aggregates the results for later retrieval
                                value.asElementNode().element().accept(parameterBuilder);
                            } else if (value.isSystemValue()){
                                ExtractedParameterValue p = processPrimitiveValue(value.asSystemValue());
                                p.setName(code);
                                p.setResourceType(fhirResource.getClass().getSimpleName());
                                allParameters.add(p);
                                if (log.isLoggable(Level.FINE)) {
                                    log.fine("Extracted Parameter '" + p.getName() + "' from Resource.");
                                }
                            } else {
                                // log and continue
                                if (log.isLoggable(Level.FINE)) {
                                    log.fine("Unable to extract value from '" + value.path() +
                                            "'; search parameter value extraction can only be performed on Elements and primitive values.");
                                }
                                // TODO: return this as a OperationOutcomeIssue with severity of WARNING
                                continue;
                            }
                        } catch (IllegalArgumentException e) {
                            // log and continue with the other parameters
                            StringBuilder msg = new StringBuilder("Skipping search parameter '" + code + "'");
                            if (sp.getId() != null) {
                                msg.append(" with id '" + sp.getId() + "'");
                            }
                            msg.append(" for resource type " + fhirResource.getClass().getSimpleName());
                            // just use the message...no need for the whole stack trace
                            msg.append(" due to \n" + e.getMessage());
                            log.log(Level.INFO, msg.toString());
                            // TODO: add an issue to the OperationOutcome in the return object
                        }
                    }
                    // retrieve the list of parameters built from all the FHIRPathElementNode values
                    List<ExtractedParameterValue> parameters = parameterBuilder.getResult();
                    for (ExtractedParameterValue p : parameters) {
                        p.setResourceType(fhirResource.getClass().getSimpleName());
                        allParameters.add(p);
                        if (log.isLoggable(Level.FINE)) {
                            log.fine("Extracted Parameter '" + p.getName() + "' from Resource.");
                        }
                    }
                }
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return allParameters;
    }

    /**
     * Create a Parameter DTO from the primitive value.
     * Note: this method only sets the value;
     * caller is responsible for setting all other fields on the created Parameter.
     */
    private ExtractedParameterValue processPrimitiveValue(FHIRPathSystemValue systemValue) {
        ExtractedParameterValue parameter = null;
        if (systemValue.isBooleanValue()) {
            TokenParmVal p = new TokenParmVal();
            if (systemValue.asBooleanValue()._boolean()) {
                p.setValueCode("true");
            } else {
                p.setValueCode("false");
            }
            parameter = p;
        } else if (systemValue.isTemporalValue()) {
            DateParmVal p = new DateParmVal();
            TemporalAccessor v = systemValue.asTemporalValue().temporal();
            java.time.Instant inst = DateTimeHandler.generateValue(v);
            p.setValueDateStart(DateTimeHandler.generateTimestamp(inst));
            p.setValueDateEnd(DateTimeHandler.generateTimestamp(inst));
            parameter = p;
        } else if (systemValue.isStringValue()) {
            StringParmVal p = new StringParmVal();
            p.setValueString(systemValue.asStringValue().string());
            parameter = p;
        } else if (systemValue.isNumberValue()) {
            NumberParmVal p = new NumberParmVal();
            p.setValueNumber(systemValue.asNumberValue().decimal());
            parameter = p;
        } else if (systemValue.isQuantityValue()) {
            QuantityParmVal p = new QuantityParmVal();
            p.setValueNumber(systemValue.asQuantityValue().value());
            p.setValueSystem("http://unitsofmeasure.org"); // FHIRPath Quantity requires UCUM units
            p.setValueCode(systemValue.asQuantityValue().unit());
        }
        return parameter;
    }

    @Override
    public void begin() throws FHIRPersistenceException {
        final String METHODNAME = "begin";
        log.entering(CLASSNAME, METHODNAME);

        try {
            if (userTransaction != null) {
                sharedConnection = createConnection();
                resourceDao.setExternalConnection(sharedConnection);
                parameterDao.setExternalConnection(sharedConnection);
                userTransaction.begin();
            }
            else if (this.getManagedConnection() != null) {
                this.getManagedConnection().setAutoCommit(false);
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while starting a transaction.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    @Override
    public void commit() throws FHIRPersistenceException {
        final String METHODNAME = "commit";
        log.entering(CLASSNAME, METHODNAME);

        try {
            if (userTransaction != null) {
                userTransaction.commit();
            } else if (this.getManagedConnection() != null) {
                this.getManagedConnection().commit();
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while committing a transaction.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            if (sharedConnection != null) {
                try {
                    sharedConnection.close();
                } catch (SQLException e) {
                    throw new FHIRPersistenceException("Failure closing DB Conection", e);
                }
                sharedConnection = null;
            }
            log.exiting(CLASSNAME, METHODNAME);
        }

    }

    @Override
    public void rollback() throws FHIRPersistenceException {
        final String METHODNAME = "rollback";
        log.entering(CLASSNAME, METHODNAME);

        try {
            if (userTransaction != null) {
                userTransaction.rollback();
            }
            else if (this.getManagedConnection() != null) {
                this.getManagedConnection().rollback();
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while rolling back a transaction.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            if (sharedConnection != null) {
                try {
                    sharedConnection.close();
                }
                catch (SQLException e) {
                    throw new FHIRPersistenceException("Failure closing DB Conection", e);
                }
                sharedConnection = null;
            }
        }
    }

    @Override
    public void setRollbackOnly() throws FHIRPersistenceException {
        final String METHODNAME = "setRollbackOnly";
        log.entering(CLASSNAME, METHODNAME);
        String errorMessage = "Unexpected error while rolling a transaction.";

        try {
            if (userTransaction != null) {
                errorMessage = "Unexpected error while marking a transaction for rollback.";
                userTransaction.setRollbackOnly();
            }
            else if (this.getManagedConnection() != null) {
                this.getManagedConnection().rollback();
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException(errorMessage);
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            if (sharedConnection != null) {
                try {
                    sharedConnection.close();
                }
                catch (SQLException e) {
                    FHIRPersistenceException fx = new FHIRPersistenceException("Failure closing DB Conection");
                    log.log(Level.SEVERE, fx.getMessage(), e);
                    throw fx;
                }
                sharedConnection = null;
            }
        }
    }

    @Override
    public OperationOutcome getHealth() throws FHIRPersistenceException {
        try (Connection connection = createConnection()){
            if (connection.isValid(2)) {
                return buildOKOperationOutcome();
            } else {
                return buildErrorOperationOutcome();
            }
        } catch (SQLException e) {
            FHIRPersistenceDataAccessException fx = new FHIRPersistenceDataAccessException("Error while validating the database connection");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
    }

    /**
     * Retrieves (via a JNDI lookup) a reference to the UserTransaction. If the JNDI lookup fails, we'll assume that
     * we're not running inside the container.
     */
    protected UserTransaction retrieveUserTransaction(String jndiName) {
        UserTransaction txn = null;
        try {
            InitialContext ctx = new InitialContext();
            txn = (UserTransaction) ctx.lookup(jndiName);
        } catch (Throwable t) {
            // ignore any exceptions here.
        }

        return txn;
    }

    @Override
    public boolean isActive() throws FHIRPersistenceException {
        boolean isActive = false;
        try {
            if (userTransaction != null) {
                isActive = (userTransaction.getStatus() == Status.STATUS_ACTIVE);
            }
        }
        catch (Throwable e) {
            String msg = "An unexpected error occurred while examining transactional status.";
            FHIRPersistenceException fx = new FHIRPersistenceException(msg);
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        return isActive;
    }

    /**
     * Converts the passed Resource Data Transfer Object collection to a collection of FHIR Resource objects.
     * @param resourceDTOList
     * @param resourceType
     * @return
     * @throws FHIRException
     * @throws IOException
     */
    // This variant uses generics and is used in history.
    // TODO: this method needs to either get merged or better differentiated with the old one used for search
    protected <T extends Resource> List<T> convertResourceDTOList(List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList, Class<T> resourceType)
                                throws FHIRException, IOException {
        final String METHODNAME = "convertResourceDTO List";
        log.entering(CLASSNAME, METHODNAME);

        List<T> resources = new ArrayList<>();
        try {
            for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                resources.add(this.convertResourceDTO(resourceDTO, resourceType, null));
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return resources;
    }

    /**
     * Converts the passed Resource Data Transfer Object collection to a collection of FHIR Resource objects.
     * @param resourceDTOList
     * @param resourceType
     * @return
     * @throws FHIRException
     * @throws IOException
     */
    // This variant doesn't use generics and is used in search.
    // TODO: this method needs to either get merged or better differentiated with the new one that supports history operation via generics.
    // Start by better understanding what happens for `_include` and `_revinclude` search results that contain multiple different types
    protected List<Resource> convertResourceDTOListOld(List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList, Class<? extends Resource> resourceType)
                                throws FHIRException, IOException {
        final String METHODNAME = "convertResourceDTO List";
        log.entering(CLASSNAME, METHODNAME);

        List<Resource> resources = new ArrayList<>();
        try {
            for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                resources.add(this.convertResourceDTO(resourceDTO, resourceType, null));
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return resources;
    }

    /**
     * Converts the passed Resource Data Transfer Object to a FHIR Resource object.
     * @param resourceDTO - A valid Resource DTO
     * @param resourceType - The FHIR type of resource to be converted.
     * @param elements - An optional filter for including only specified elements inside a Resource.
     * @return Resource - A FHIR Resource object representation of the data portion of the passed Resource DTO.
     * @throws FHIRException
     * @throws IOException
     */
    private <T extends Resource> T convertResourceDTO(com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO,
            Class<T> resourceType,
            List<String> elements) throws FHIRException, IOException {
        final String METHODNAME = "convertResourceDTO";
        log.entering(CLASSNAME, METHODNAME);
        T resource = null;
        try {
            if (resourceDTO != null) {
                InputStream in = new GZIPInputStream(new ByteArrayInputStream(resourceDTO.getData()));
                if (elements != null) {
                    // parse/filter the resource using elements
                    resource = FHIRParser.parser(Format.JSON).as(FHIRJsonParser.class).parseAndFilter(in, elements);
                    if (resourceType.equals(resource.getClass()) && !FHIRUtil.hasTag(resource, SearchConstants.SUBSETTED_TAG)) {
                        // add a SUBSETTED tag to this resource to indicate that its elements have been filtered
                        resource = FHIRUtil.addTag(resource, SearchConstants.SUBSETTED_TAG);
                    }
                } else {
                    resource = FHIRParser.parser(Format.JSON).parse(in);
                }
                in.close();
            }
        } finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return resource;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public FHIRPersistenceTransaction getTransaction() {
        return this;
    }

    @Override
    public boolean isDeleteSupported() {
        return true;
    }

    private FHIRDbDAO getBaseDao() {
        return baseDao;
    }

    private void setBaseDao(FHIRDbDAO baseDao) {
        this.baseDao = baseDao;
    }

    private Connection getManagedConnection() {
        return managedConnection;
    }

    private void setManagedConnection(Connection managedConnection) {
        this.managedConnection = managedConnection;
    }

    private OperationOutcome buildOKOperationOutcome() {
        return FHIRUtil.buildOperationOutcome("All OK", IssueType.INFORMATIONAL, IssueSeverity.INFORMATION);
    }

    private OperationOutcome buildErrorOperationOutcome() {
        return FHIRUtil.buildOperationOutcome("The database connection was not valid", IssueType.NO_STORE, IssueSeverity.ERROR);
    }

    private Connection createConnection() throws FHIRPersistenceDBConnectException {
        FHIRDbDAOImpl dao = new FHIRDbDAOImpl();
        return dao.getConnection();
    }

    /**
     * Enroll in an existing transaction.
     *
     * <p>Enrolling in an existing transaction is an alternative to beginning a new transaction. Calling this method
     * gives implementations a chance to create necessary resources associated with a given unit of work when that
     * unit of work is performed under an existing user-managed transaction.
     *
     * @throws FHIRPersistenceException
     */
    @Override
    public void unenroll() throws FHIRPersistenceException {
        final String METHODNAME = "unenroll";
        log.entering(CLASSNAME, METHODNAME);

        try {
            if (userTransaction != null) {
                if (sharedConnection != null) {
                        sharedConnection.close();
                }
            } else {
                throw new FHIRPersistenceException("unenroll should be called only if userTransaction is not null!");
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while unenroll.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            sharedConnection = null;
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /**
     * Unenroll from the existing transaction.
     *
     * <p>Unenrolling from an existing transaction is an alternative to committing or rolling back the transaction. Calling
     * this method gives implementations a chance to release resources associated with a given unit of work when that
     * unit of work is performed under an existing user-managed transaction.
     *
     * @throws FHIRPersistenceException
     */
    @Override
    public void enroll() throws FHIRPersistenceException {
        final String METHODNAME = "enroll";
        log.entering(CLASSNAME, METHODNAME);

        try {
            if (userTransaction != null) {
                sharedConnection = createConnection();
                resourceDao.setExternalConnection(sharedConnection);
                parameterDao.setExternalConnection(sharedConnection);
            } else {
                throw new FHIRPersistenceException("enroll should be called only if userTransaction is not null!");
            }
        }
        catch (Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while enroll.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }

    }
}
