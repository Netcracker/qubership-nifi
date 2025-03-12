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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
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
import org.apache.nifi.processor.util.StandardValidators;
import org.qubership.nifi.NiFiUtils;
import org.qubership.nifi.processors.query.AbstractRsToJsonWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@Tags({"JSON", "DB"})
@CapabilityDescription("Fetches data from DB table into JSON using either query (Custom Query) or table (Table) and \n"
        + " list of columns (Columns To Return). This processor works in batched mode: it collects FlowFiles until \n"
        + " batch size limit is reached and then processes batch. This processor can accept incoming connections; \n"
        + " the behavior of the processor is different whether incoming connections are provided: \n"
        + "-If no incoming connection(s) are specified, the processor will generate SQL queries on the specified \n"
        + " processor schedule. \n"
        + "-If incoming connection(s) are specified and no FlowFile is available to a processor task, no work \n"
        + "will be performed. \n"
        + "-If incoming connection(s) are specified and a FlowFile is available to a processor task, query \n"
        + " will be executed when processing the next FlowFile. \n")
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "Sets mime.type = application/json"),
        @WritesAttribute(attribute = "fetch.id", description = "Sets to UUID"),
        @WritesAttribute(attribute = "fetch.count", description = "Sets to number of batches"),
        @WritesAttribute(attribute = "rows.count", description = "Sets to total number of fetched rows"),
        @WritesAttribute(attribute = "extraction.error", description = "Sets to error stacktrace, in case of exception")
})
public class FetchTableToJson extends AbstractProcessor {

    private static final String ATTR_FETCH_COUNT = "fetch.count";
    private static final String ATTR_ROWS_COUNT = "rows.count";
    private static final String ATTR_FETCH_ID = "fetch.id";
    private static final String EXTRACTION_ERROR = "extraction.error";

    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor
            .Builder()
            .name("Database Connection Pooling Service")
            .description("The Controller Service that is used to obtain a connection to the database.")
            .required(true)
            .identifiesControllerService(DBCPService.class)
            .build();

    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.
            Builder()
            .name("batch-size")
            .displayName("Batch Size")
            .description("The maximum number of rows from the result set to be"
                    + " saved in a single FlowFile.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(
                    ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor FETCH_SIZE = new PropertyDescriptor.Builder()
            .name("fetch-size")
            .displayName("Fetch Size")
            .description("The number of result rows to be fetched from the"
                    + " result set at a time. This is a hint to the database driver and may not be "
                    + "honored and/or exact. If the value specified is zero, then the hint is ignored.")
            .defaultValue("1")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    public static final PropertyDescriptor COLUMN_NAMES = new PropertyDescriptor.Builder()
            .name("columns-to-return")
            .displayName("Columns To Return")
            .description("A comma-separated list of column names to be used in the query. If your database requires "
                    + "special treatment of the names (quoting, e.g.), each name should include such treatment. If no "
                    + "column names are supplied, all columns in the specified table will be returned. "
                    + "NOTE: It is important to use consistent column names for a given table for incremental"
                    + " fetch to work properly.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor TABLE = new PropertyDescriptor.Builder()
            .name("table")
            .displayName("Table Name")
            .description("The name of the database table to be queried. If Custom Query is set, "
                    + "this property is ignored.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor CUSTOM_QUERY = new PropertyDescriptor.Builder()
            .name("custom-query")
            .displayName("Custom Query")
            .description("Custom query. Would be used instead of tables and columns properties if specified.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor WRITE_BY_BATCH = new PropertyDescriptor.Builder()
            .name("write-by-batch")
            .displayName("Write By Batch")
            .description("Write a type that corresponds to the behavior of appearing FlowFiles in the queue.")
            .required(false)
            .allowableValues("true", "false")
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully created FlowFile from SQL query result set.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("This relationship is only used when SQL query execution (using an incoming FlowFile) failed."
                    + "The incoming FlowFile will be penalized and routed to this relationship. "
                    + "If no incoming connection(s) are specified, this relationship is unused.")
            .build();

    public static final Relationship REL_TOTAL_COUNT = new Relationship.Builder()
            .name("count")
            .description("One FlowFile per request with attributes:"
                    + ATTR_ROWS_COUNT + " - total of fetched rows, "
                    + ATTR_FETCH_COUNT + " - number of batches.")
            .build();

    private String staticQuery;
    private boolean isWriteByBatch;
    private int batchSize;
    private int fetchSize;

    private Set<Relationship> relationships;
    private List<PropertyDescriptor> propDescriptors;

    /**
     * Constructor for class FetchTableToJson.
     */
    public FetchTableToJson() {
        final Set<Relationship> rel = new HashSet<>();
        rel.add(REL_SUCCESS);
        rel.add(REL_FAILURE);
        rel.add(REL_TOTAL_COUNT);
        relationships = Collections.unmodifiableSet(rel);

        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(DBCP_SERVICE);
        pds.add(TABLE);
        pds.add(COLUMN_NAMES);
        pds.add(CUSTOM_QUERY);
        pds.add(BATCH_SIZE);
        pds.add(FETCH_SIZE);
        pds.add(WRITE_BY_BATCH);


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

        staticQuery = getQuery(context, null);
        isWriteByBatch = context.getProperty(WRITE_BY_BATCH).asBoolean();
        batchSize = context.getProperty(BATCH_SIZE).asInteger();
        fetchSize = Integer.parseInt(context.getProperty(FETCH_SIZE).getValue());
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
    public void onTrigger(ProcessContext context, ProcessSession session) {
        FlowFile invocationFile = session.get();
        String fetchId = null;
        Map<String, String> attributes = Collections.emptyMap();

        if (isRequestInvalid(invocationFile, context)) {
            return;
        }

        String query = staticQuery;

        if (context.hasIncomingConnection() && invocationFile != null) {
            attributes = invocationFile.getAttributes();
            fetchId = invocationFile.getAttribute(ATTR_FETCH_ID);
            query = getQuery(context, invocationFile);
        }

        if (fetchId == null) {
            fetchId = UUID.randomUUID().toString();
        }

        try (
                Connection con = createConnection(context);
                PreparedStatement ps = createPreparedStatement(con, query);
        ) {
            String finalFetchId = fetchId;
            Map<String, String> finalAttributes = attributes;
            String dbUrl = con.getMetaData().getURL();
            final List<FlowFile> allFlowFiles = new ArrayList<>();

            AbstractRsToJsonWriter<ProcessSession> writer = new AbstractRsToJsonWriter<ProcessSession>() {
                public boolean getIsFirst() {
                    return isFirst;
                }

                public void setFirst(boolean first) {
                    isFirst = first;
                }

                private boolean isFirst = true;

                @Override
                protected void writeJson(JsonNode result, ProcessSession session) {
                    FlowFile flowFile = session.write(
                            session.putAllAttributes(session.create(), finalAttributes),
                            out -> NiFiUtils.MAPPER.writeValue(out, result)
                    );
                    if (invocationFile != null) {
                        if (!isWriteByBatch) {
                            allFlowFiles.add(flowFile);
                        } else if (getIsFirst()) {
                            //report for invocationFile:
                            session.getProvenanceReporter().fork(invocationFile, Collections.singletonList(flowFile));
                            session.remove(invocationFile);
                            setFirst(false);
                        }
                    }
                    flowFile = session.putAttribute(flowFile, ATTR_FETCH_ID, finalFetchId);
                    session.putAttribute(flowFile, CoreAttributes.MIME_TYPE.key(), "application/json");
                    session.transfer(flowFile, REL_SUCCESS);

                    if (isWriteByBatch) {
                        session.getProvenanceReporter().fetch(flowFile, dbUrl);
                        session.commit();
                    }
                }
            };
            writer.setBatchSize(batchSize);

            long totalRowCount = 0;
            ps.setFetchSize(this.fetchSize);
            boolean originalAutocommit = con.getAutoCommit();
            //to allow PostgreSQL to use cursors, we have to set autocommit to false:
            con.setAutoCommit(false);
            try (ResultSet resultSet = ps.executeQuery()) {
                totalRowCount = writer.write(resultSet, session, getLogger());
            } finally {
                try {
                    con.commit();
                } finally {
                    con.setAutoCommit(originalAutocommit);
                }
            }
            if (invocationFile != null && !allFlowFiles.isEmpty() && !isWriteByBatch) {
                session.getProvenanceReporter().fork(invocationFile, allFlowFiles);
                for (FlowFile flowFile : allFlowFiles) {
                    session.getProvenanceReporter().fetch(flowFile, dbUrl);
                }
            }
            long totalBatchCount = countTotalBatch(totalRowCount);

            FlowFile countFlowFile = session.putAllAttributes(session.create(), finalAttributes);

            session.putAttribute(countFlowFile, ATTR_ROWS_COUNT, String.valueOf(totalRowCount));
            session.putAttribute(countFlowFile, ATTR_FETCH_COUNT, String.valueOf(totalBatchCount));
            session.putAttribute(countFlowFile, ATTR_FETCH_ID, fetchId);
            session.putAttribute(countFlowFile, CoreAttributes.MIME_TYPE.key(), "application/json");
            session.transfer(countFlowFile, REL_TOTAL_COUNT);

            removeInvocationFlowFile(invocationFile, session);
        } catch (Exception e) {
            FlowFile exFlowFile = invocationFile != null && !isWriteByBatch ? invocationFile : session.create();
            session.putAllAttributes(exFlowFile, attributes);
            session.putAttribute(exFlowFile, EXTRACTION_ERROR, ExceptionUtils.getStackTrace(e));
            session.transfer(exFlowFile, REL_FAILURE);

            getLogger().error("An exception occurs during the FetchTableToJson processing", e);
        }
    }

    private boolean isRequestInvalid(FlowFile invocationFile, ProcessContext context) {
        return context.hasIncomingConnection() && invocationFile == null && context.hasNonLoopConnection();
    }

    private long countTotalBatch(long totalRowCount) {
        double totalBatchCount = Math.ceil((double) totalRowCount / batchSize);

        return (long) totalBatchCount;
    }

    private Connection createConnection(ProcessContext context) {
        return getDbcpService(context).getConnection(Collections.emptyMap());
    }

    private PreparedStatement createPreparedStatement(Connection con, String inputQuery) throws SQLException {
        return con.prepareStatement(inputQuery);
    }

    private DBCPService getDbcpService(ProcessContext context) {
        return context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
    }

    private void removeInvocationFlowFile(FlowFile invocationFile, ProcessSession session) {
        if (invocationFile != null && !isWriteByBatch) {
            session.remove(invocationFile);
        }
    }

    private String getQuery(ProcessContext context, FlowFile invocationFile) {
        String query = null;
        if (invocationFile != null) {
            query = context.getProperty(CUSTOM_QUERY).evaluateAttributeExpressions(invocationFile).getValue();
            if (query == null) {
                query = "select "
                        + context.getProperty(COLUMN_NAMES).evaluateAttributeExpressions(invocationFile).getValue()
                        + " from "
                        + context.getProperty(TABLE).evaluateAttributeExpressions(invocationFile).getValue();
            }
        } else {
            query = context.getProperty(CUSTOM_QUERY).evaluateAttributeExpressions().getValue();
            if (query == null) {
                query = "select "
                        + context.getProperty(COLUMN_NAMES).evaluateAttributeExpressions().getValue()
                        + " from "
                        + context.getProperty(TABLE).evaluateAttributeExpressions().getValue();
            }
        }

        return query;
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
