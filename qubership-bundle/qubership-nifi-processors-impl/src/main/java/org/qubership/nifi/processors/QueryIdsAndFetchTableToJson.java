/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.nifi.processors;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.qubership.nifi.NiFiUtils;
import org.qubership.nifi.service.PreparedStatementProvider;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.qubership.nifi.processors.query.AbstractRsToJsonWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@Tags({"JSON", "DB"})
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "Sets mime.type = application/json"),
        @WritesAttribute(attribute = "fetch.id", description = "Sets to UUID"),
        @WritesAttribute(attribute = "fetch.count", description = "Sets to number of batches"),
        @WritesAttribute(attribute = "rows.count", description = "Sets to total number of fetched rows"),
        @WritesAttribute(attribute = "extraction.error", description = "Sets to error stacktrace, in case of exception")
})
public class QueryIdsAndFetchTableToJson extends AbstractProcessor {

    private static final String ATTR_FETCH_COUNT = "fetch.count";
    private static final String ATTR_ROWS_COUNT = "rows.count";
    private static final String ATTR_FETCH_ID = "fetch.id";
    private static final String EXTRACTION_ERROR = "extraction.error";
    private static final String ORACLE_CONDITION = "select /*+ cardinality (t 10) */ t.column_value"
            + " from table(cast(? as arrayofstrings)) t";
    private static final String POSTGRES_CONDITION = "select unnest(?)";
    private static final String EMPTY_CONDITION = "NULL";

    /**
     * Source DB Connection property descriptor.
     */
    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("Database Connection Pooling Service")
            .description("The Controller Service that is used to obtain a connection to the source database.")
            .required(true)
            .identifiesControllerService(DBCPService.class)
            .build();

    /**
     * Mapping DB Connection property descriptor.
     */
    public static final PropertyDescriptor IDS_DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("Ids Database Connection Pooling Service")
            .description("The Controller Service that is used to obtain a connection to the mapping database.")
            .required(true)
            .identifiesControllerService(DBCPService.class)
            .build();

    /**
     * Prepared statement provider property descriptor.
     */
    public static final PropertyDescriptor PS_PROVIDER_SERVICE = new PropertyDescriptor.Builder()
            .name("prepared-statement-provider-service")
            .displayName("Prepared Statement Provider")
            .description("The Controller Service that is used to create a prepared statement.")
            .required(true)
            .identifiesControllerService(PreparedStatementProvider.class)
            .build();

    /**
     * Batch size property descriptor.
     */
    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("batch-size")
            .displayName("Batch Size")
            .description("The maximum number of rows in table from source database from the result set to be saved "
                    + " in a single FlowFile.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Ids batch size property descriptor.
     */
    public static final PropertyDescriptor IDS_BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("ids-batch-size")
            .displayName("Ids Batch Size")
            .description("The maximum number of rows in table from ids database from the result.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Fetch size property descriptor.
     */
    public static final PropertyDescriptor FETCH_SIZE = new PropertyDescriptor.Builder()
            .name("fetch-size")
            .displayName("Fetch Size")
            .description("The number of result rows to be fetched from the result set at a time. "
                    + "This is a hint to the database driver and may not be honored and/or exact. "
                    + " If the value specified is zero, then the hint is ignored.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Ids fetch size property descriptor.
     */
    public static final PropertyDescriptor IDS_FETCH_SIZE = new PropertyDescriptor.Builder()
            .name("ids-fetch-size")
            .displayName("Ids Fetch Size")
            .description("The number of result rows to be fetched from the result set at a time. "
                    + "This is a hint to the database driver and may not be "
                    + "honored and/or exact. If the value specified is zero, then the hint is ignored.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Custom query property descriptor.
     */
    public static final PropertyDescriptor CUSTOM_QUERY = new PropertyDescriptor.Builder()
            .name("custom-query")
            .displayName("Custom Query")
            .description("Custom query for Source Data Base.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Ids custom query property descriptor.
     */
    public static final PropertyDescriptor IDS_CUSTOM_QUERY = new PropertyDescriptor.Builder()
            .name("ids-custom-query")
            .displayName("Ids Custom Query")
            .description("Custom request to get a list of id.")
            .defaultValue("${mapping.db.query}")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Write by batch property descriptor.
     */
    public static final PropertyDescriptor WRITE_BY_BATCH = new PropertyDescriptor.Builder()
            .name("write-by-batch")
            .displayName("Write By Batch")
            .description("Write a type that corresponds to the behavior of appearing FlowFiles in the queue.")
            .required(false)
            .allowableValues("true", "false")
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    /**
     * Ids write by batch property descriptor.
     */
    public static final PropertyDescriptor IDS_WRITE_BY_BATCH = new PropertyDescriptor.Builder()
            .name("ids-write-by-batch")
            .displayName("Ids Write By Batch")
            .description("Write a type that corresponds to the behavior of appearing FlowFiles in the queue.")
            .required(false)
            .allowableValues("true", "false")
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully created FlowFile from SQL query result set.")
            .build();

    /**
     * Failure relationship.
     */
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("This relationship is only used when SQL query execution (using an incoming FlowFile) failed."
                    + " The incoming FlowFile will be penalized and routed to this relationship. "
                    + "If no incoming connection(s) are specified, this relationship is unused.")
            .build();

    /**
     * Count relationship.
     */
    public static final Relationship REL_TOTAL_COUNT = new Relationship.Builder()
            .name("count")
            .description("One FlowFile per request with attributes:" + ATTR_ROWS_COUNT + " - total of fetched rows, "
                    + ATTR_FETCH_COUNT + " - number of batches.")
            .build();

    private String query;
    private String idsQuery;
    private int batchSize;
    private int fetchSize;
    private int idsBatchSize;
    private int idsFetchSize;
    private boolean isWriteByBatch;
    private boolean isIdsWriteByBatch;
    private PreparedStatementProvider statementProducer;

    private Set<Relationship> relationships;
    private List<PropertyDescriptor> propDescriptors;

    /**
     * Constructor for class QueryDatabaseToCSV.
     */
    public QueryIdsAndFetchTableToJson() {
        final Set<Relationship> rel = new HashSet<>();
        rel.add(REL_SUCCESS);
        rel.add(REL_FAILURE);
        rel.add(REL_TOTAL_COUNT);
        relationships = Collections.unmodifiableSet(rel);

        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(DBCP_SERVICE);
        pds.add(IDS_DBCP_SERVICE);
        pds.add(PS_PROVIDER_SERVICE);
        pds.add(CUSTOM_QUERY);
        pds.add(IDS_CUSTOM_QUERY);
        pds.add(BATCH_SIZE);
        pds.add(IDS_BATCH_SIZE);
        pds.add(FETCH_SIZE);
        pds.add(IDS_FETCH_SIZE);
        pds.add(WRITE_BY_BATCH);
        pds.add(IDS_WRITE_BY_BATCH);


        propDescriptors = Collections.unmodifiableList(pds);
    }

    /**
     * This method will be called before any onTrigger calls and will be called once each time the Processor
     * is scheduled to run. This happens in one of two ways: either the user clicks to schedule the component to run,
     * or NiFi restarts with the "auto-resume state" configuration set to true (the default) and the component
     * is already running.
     *
     * @param context
     */
    @OnScheduled
    public void onScheduled(ProcessContext context) {
        query = context.getProperty(CUSTOM_QUERY).evaluateAttributeExpressions().getValue();
        idsQuery = context.getProperty(IDS_CUSTOM_QUERY).evaluateAttributeExpressions().getValue();
        isWriteByBatch = context.getProperty(WRITE_BY_BATCH).asBoolean();
        isIdsWriteByBatch = context.getProperty(IDS_WRITE_BY_BATCH).asBoolean();
        batchSize = context.getProperty(BATCH_SIZE).asInteger();
        fetchSize = Integer.parseInt(context.getProperty(FETCH_SIZE).getValue());
        idsBatchSize = context.getProperty(IDS_BATCH_SIZE).asInteger();
        idsFetchSize = Integer.parseInt(context.getProperty(IDS_FETCH_SIZE).getValue());
        statementProducer = context.getProperty(PS_PROVIDER_SERVICE)
                        .asControllerService(PreparedStatementProvider.class);
    }

    /**
     * The method called when this processor is triggered to operate by the controller.
     * When this method is called depends on how this processor is configured within a controller
     * to be triggered (timing or event based).
     * Params:
     * context – provides access to convenience methods for obtaining property values, delaying the scheduling of the
     *           processor, provides access to Controller Services, etc.
     * session – provides access to a ProcessSession, which can be used for accessing FlowFiles, etc.
     */
    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile invocationFile = session.get();
        List<String> id = new ArrayList<>();
        String fetchId = null;
        long totalRowCount = 0;
        long totalBatchCount = 0;
        long[] totalCount;
        Map<String, String> attributes = Collections.emptyMap();

        if (isRequestInvalid(invocationFile, context)) {
            return;
        }

        if (context.hasIncomingConnection() && invocationFile != null) {
            attributes = invocationFile.getAttributes();
            fetchId = invocationFile.getAttribute(ATTR_FETCH_ID);
            query = context.getProperty(CUSTOM_QUERY).evaluateAttributeExpressions(invocationFile).getValue();
            idsQuery = context.getProperty(IDS_CUSTOM_QUERY).evaluateAttributeExpressions(invocationFile).getValue();
            if (isWriteByBatch) {
                session.remove(invocationFile);
            }
        }

        if (fetchId == null) {
            fetchId = UUID.randomUUID().toString();
        }

        try {
            if (StringUtils.isNotEmpty(context.getProperty(IDS_CUSTOM_QUERY)
                    .evaluateAttributeExpressions(invocationFile).getValue())) {
                try (Connection idsConnection = createIdsConnection(context);) {
                    boolean originalAutocommit = idsConnection.getAutoCommit();
                    //to allow PostgreSQL to use cursors, we have to set autocommit to false:
                    idsConnection.setAutoCommit(false);
                    try (
                            PreparedStatement idsPreparedStatement = createIdsPreparedStatement(idsConnection);
                            ResultSet idsResultSet = idsPreparedStatement.executeQuery();
                    ) {
                        int rowCount = 0;
                        if (idsResultSet.isBeforeFirst()) {
                            if (context.getProperty(PS_PROVIDER_SERVICE).asControllerService()
                                    .toString().contains("Oracle")) {
                                query = query.replace("#SOURCE_IDS#", ORACLE_CONDITION);
                            } else {
                                query = query.replace("#SOURCE_IDS#", POSTGRES_CONDITION);
                            }
                            while (idsResultSet.next()) {
                                id.add(idsResultSet.getString("source_id"));
                                rowCount++;

                                if (isIdsWriteByBatch && rowCount == idsBatchSize) {
                                    rowCount = 0;
                                    totalCount = fetchTableToJson(fetchId, attributes, session, context,
                                            invocationFile, id);
                                    totalRowCount = totalRowCount + totalCount[0];
                                    totalBatchCount = totalBatchCount + totalCount[1];
                                    id = new ArrayList<>();
                                }

                            }
                            if (rowCount > 0) {
                                totalCount = fetchTableToJson(fetchId, attributes, session, context,
                                        invocationFile, id);
                                totalRowCount = totalRowCount + totalCount[0];
                                totalBatchCount = totalBatchCount + totalCount[1];
                            }
                        } else {
                            query = query.replace("#SOURCE_IDS#", EMPTY_CONDITION);
                            totalCount = fetchTableToJson(fetchId, attributes, session, context,
                                    invocationFile, id);
                            totalRowCount = totalRowCount + totalCount[0];
                            totalBatchCount = totalBatchCount + totalCount[1];
                        }
                    } finally {
                        try {
                            idsConnection.commit();
                        } finally {
                            idsConnection.setAutoCommit(originalAutocommit);
                        }
                    }

                } catch (SQLException e) {
                    throw new ProcessException("Database problem during execution of " + this.getClass().getName(), e);
                }
            } else {
                totalCount = fetchTableToJson(fetchId, attributes, session, context, invocationFile, id);
                totalRowCount = totalRowCount + totalCount[0];
                totalBatchCount = totalBatchCount + totalCount[1];
            }

            FlowFile countFlowFile = session.putAllAttributes(session.create(), attributes);

            session.putAttribute(countFlowFile, CoreAttributes.MIME_TYPE.key(), "application/json");
            session.putAttribute(countFlowFile, ATTR_ROWS_COUNT, String.valueOf(totalRowCount));
            session.putAttribute(countFlowFile, ATTR_FETCH_COUNT, String.valueOf(totalBatchCount));
            session.putAttribute(countFlowFile, ATTR_FETCH_ID, fetchId);
            session.transfer(countFlowFile, REL_TOTAL_COUNT);
        } catch (Exception ex) {
            FlowFile exFlowFile = session.putAttribute(
                    invocationFile != null && !isWriteByBatch ? invocationFile
                            : session.putAllAttributes(session.create(), attributes),
                    EXTRACTION_ERROR,
                    ExceptionUtils.getStackTrace(ex)
            );
            getLogger().error("An exception occured during the QueryIdsAndFetchTableToJson processing", ex);
            session.transfer(exFlowFile, REL_FAILURE);
        }
    }

    private boolean isRequestInvalid(FlowFile invocationFile, ProcessContext context) {
        return context.hasIncomingConnection() && invocationFile == null && context.hasNonLoopConnection();
    }

    private Connection createConnection(ProcessContext context) {
        return getDbcpService(context).getConnection(Collections.emptyMap());
    }

    private Connection createIdsConnection(ProcessContext context) {
        return getIdsDbcpService(context).getConnection(Collections.emptyMap());
    }

    private DBCPService getDbcpService(ProcessContext context) {
        return context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
    }

    private DBCPService getIdsDbcpService(ProcessContext context) {
        return context.getProperty(IDS_DBCP_SERVICE).asControllerService(DBCPService.class);
    }

    private long[] fetchTableToJson(
            String fetchId,
            Map<String, String> attributes,
            ProcessSession session,
            ProcessContext context,
            FlowFile invocationFile,
            List<String> id) throws SQLException {
        long[] totalCount = new long[2];
        long totalRowCount = 0;
        long totalBatchCount = 0;
        try (
                Connection connection = createConnection(context);
                PreparedStatement preparedStatement =  id.isEmpty() ? connection.prepareStatement(query) :
                        getStatementProducer().createPreparedStatement(query, context, id, connection);
        ) {
            String dbUrl = connection.getMetaData().getURL();
            String finalFetchId = fetchId;
            Map<String, String> finalAttributes = attributes;

            AbstractRsToJsonWriter<ProcessSession> writer = new AbstractRsToJsonWriter<ProcessSession>() {
                @Override
                protected void writeJson(JsonNode result, ProcessSession session) {
                    FlowFile flowFile = session.write(
                            session.putAllAttributes(session.create(), finalAttributes),
                            out -> NiFiUtils.MAPPER.writeValue(out, result)
                    );

                    flowFile = session.putAttribute(flowFile, ATTR_FETCH_ID, finalFetchId);
                    session.putAttribute(flowFile, CoreAttributes.MIME_TYPE.key(), "application/json");
                    session.getProvenanceReporter().fetch(flowFile, dbUrl);
                    session.transfer(flowFile, REL_SUCCESS);

                    if (isWriteByBatch) {
                        session.commit();
                    }
                }
            };
            writer.setBatchSize(batchSize);
            preparedStatement.setFetchSize(this.fetchSize);

            boolean originalAutocommit = connection.getAutoCommit();
            //to allow PostgreSQL to use cursors, we have to set autocommit to false:
            connection.setAutoCommit(false);
            try (ResultSet resultSet = preparedStatement.executeQuery();) {
                totalRowCount = writer.write(resultSet, session, getLogger());
            } finally {
                try {
                    connection.commit();
                } finally {
                    connection.setAutoCommit(originalAutocommit);
                }
            }

            totalBatchCount = countTotalBatch(totalRowCount);


            removeInvocationFlowFile(invocationFile, session);
        } catch (Exception e) {
            throw new SQLException("An exception occurs during the FetchTableToJson processing", e);
        }
        totalCount[0] = totalRowCount;
        totalCount[1] = totalBatchCount;
        return totalCount;
    }

    private PreparedStatement createIdsPreparedStatement(Connection con) throws SQLException {
        PreparedStatement ps = con.prepareStatement(idsQuery);
        ps.setFetchSize(idsFetchSize);
        return ps;
    }

    /**
     * Returns a Prepared Statement to connect to the database.
     * @return PreparedStatementProvider objects
     */
    protected PreparedStatementProvider getStatementProducer() {
        return statementProducer;
    }

    private void removeInvocationFlowFile(FlowFile invocationFile, ProcessSession session) {
        if (invocationFile != null && !isWriteByBatch) {
            session.remove(invocationFile);
        }
    }

    private long countTotalBatch(long totalRowCount) {
        double totalBatchCount = Math.ceil((double) totalRowCount / batchSize);

        return (long) totalBatchCount;
    }

    /**
     * Returns:
     * Set of all relationships this processor expects to transfer a flow file to.
     * An empty set indicates this processor does not have any destination relationships.
     * Guaranteed non-null.
     *
     */
    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    /**
     * Returns a List of all PropertyDescriptors that this component supports.
     * Returns:
     * PropertyDescriptor objects this component currently supports
     *
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }
}
