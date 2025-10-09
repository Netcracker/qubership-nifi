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

package org.qubership.nifi.processors.extract;

import lombok.SneakyThrows;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.OutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.BufferedOutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Clob;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;

@SideEffectFree
@Tags({"CSV", "DB", "Database", "Query"})
@CapabilityDescription("Fetches data from DB using specified query and transforms it to CSV in particular CSV format."
        + "The processor allows to split query result into several FlowFiles and select CSV format for output.")
@WritesAttributes({
    @WritesAttribute(attribute = "extraction.error", description = "Error message with stacktrace for the case, "
            + "when the processor failed to extract DB data to CSV")
})
public class QueryDatabaseToCSV extends AbstractProcessor {

    /**
     * Database Connection Pooling Service property descriptor.
     */
    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("Database Connection Pooling Service")
            .description("The Controller Service that is used to obtain a connection to the database.")
            .required(true)
            .identifiesControllerService(DBCPService.class)
            .build();

    /**
     * Custom Query property descriptor.
     */
    public static final PropertyDescriptor CUSTOM_QUERY = new PropertyDescriptor.Builder()
            .name("custom-query")
            .displayName("Custom Query")
            .description("Query to run against DB to get CSV data from.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * CSV Format property descriptor.
     */
    public static final PropertyDescriptor CSV_FORMAT = new PropertyDescriptor.Builder()
            .name("csv-format")
            .displayName("CSV Format")
            .description("CSV Format to use for data extraction")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .defaultValue(CSVFormat.Predefined.Default.name())
            .allowableValues(CSVFormat.Predefined.values())
            .build();

    /**
     * Write By Batch property descriptor.
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
     * Batch Size property descriptor.
     */
    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("batch-size")
            .displayName("Batch Size")
            .description("The maximum number of rows from the result set to be saved in a single FlowFile. "
                    + "If set to 0, then the whole result set is saved to a single FlowFile.")
            .defaultValue("0")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Fetch Size property descriptor.
     */
    public static final PropertyDescriptor FETCH_SIZE = new PropertyDescriptor.Builder()
            .name("fetch-size")
            .displayName("Fetch Size")
            .description("The number of result rows to be fetched from the result set at a time. "
                    + " This is a hint to the database driver and may not be honored and/or exact."
                    + " If the value specified is zero, then the hint is ignored.")
            .defaultValue("10000")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
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
                    + "The incoming FlowFile will be penalized and routed to this relationship. "
                    + "If no incoming connection(s) are specified, this relationship is unused.")
            .build();

    private Set<Relationship> relationships;
    private List<PropertyDescriptor> propDescriptors;

    private String query;
    private boolean isWriteByBatch;
    private int batchSize;
    private int fetchSize;
    private CSVFormat csvFormat;

    private static final String EXTRACTION_ERROR = "extraction.error";

    /**
     * Constructor for class QueryDatabaseToCSV.
     */
    public QueryDatabaseToCSV() {
        final Set<Relationship> rel = new HashSet<>();
        rel.add(REL_SUCCESS);
        rel.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(rel);

        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(DBCP_SERVICE);
        pds.add(CUSTOM_QUERY);
        pds.add(CSV_FORMAT);
        pds.add(BATCH_SIZE);
        pds.add(FETCH_SIZE);
        pds.add(WRITE_BY_BATCH);
        propDescriptors = Collections.unmodifiableList(pds);
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
        FlowFile inFlowFile = session.get();
        Map<String, String> attributes = Collections.emptyMap();
        if (isRequestInvalid(inFlowFile, context)) {
            return;
        }

        if (context.hasIncomingConnection() && inFlowFile != null) {
            attributes = inFlowFile.getAttributes();
            query = context.getProperty(CUSTOM_QUERY).evaluateAttributeExpressions(inFlowFile).getValue();
            if (isWriteByBatch) {
                session.remove(inFlowFile);
            }
        }

        try (
                Connection con = createConnection(context);
        ) {
            String dbUrl = con.getMetaData().getURL();
            boolean originalAutocommit = con.getAutoCommit();
            //to allow PostgreSQL to use cursors, we have to set autocommit to false:
            con.setAutoCommit(false);
            try (PreparedStatement ps = createPreparedStatement(con);
                ResultSet rs = ps.executeQuery();) {
                final int columnCount = rs.getMetaData().getColumnCount();
                final CSVFormat localCsvFormat = csvFormat.withHeader(rs);

                while (rs.next()) {
                    FlowFile ff = session.write(session.putAllAttributes(session.create(), attributes),
                            new OutputStreamCallback() {
                                @SneakyThrows
                                @Override
                                public void process(OutputStream out) throws IOException {
                                    int rowCount = 0;
                                    try (CSVPrinter printer = new CSVPrinter(
                                            new PrintWriter(new BufferedOutputStream(out)),
                                            localCsvFormat)) {
                                        do {
                                            for (int i = 1; i <= columnCount; ++i) {
                                                Object cellValue = rs.getObject(i);
                                                if (cellValue instanceof String) {
                                                    printer.print(String.valueOf(cellValue));
                                                } else if (cellValue instanceof Clob) {
                                                    Reader characterStream = ((Clob) cellValue).getCharacterStream();
                                                    StringWriter sw = new StringWriter();
                                                    IOUtils.copy(characterStream, sw);
                                                    printer.print(sw.toString());
                                                } else if (cellValue instanceof Blob) {
                                                    Blob blob = (Blob) cellValue;
                                                    int len = (int) blob.length();
                                                    byte[] bytes = blob.getBytes(1, len);
                                                    printer.print(Hex.encodeHexString(bytes));
                                                } else {
                                                    printer.print(cellValue);
                                                }
                                            }
                                            printer.println();
                                            rowCount++;

                                            if (batchSize != 0 && rowCount == batchSize) {
                                                printer.flush();
                                                rowCount = 0;
                                                break;
                                            }
                                        } while (rs.next());

                                        if (rowCount > 0) {
                                            printer.flush();
                                        }
                                    }
                                }
                            });
                    session.getProvenanceReporter().fetch(ff, dbUrl);
                    session.transfer(ff, REL_SUCCESS);
                    if (isWriteByBatch) {
                        session.commit();
                    }
                }
            } finally {
                try {
                    con.commit();
                } finally {
                    con.setAutoCommit(originalAutocommit);
                }
            }
            removeInvocationFlowFile(inFlowFile, session);
        } catch (Exception e) {
            FlowFile exFlowFile = session.putAttribute(
                    inFlowFile != null && !isWriteByBatch ? inFlowFile : session.putAllAttributes(session.create(),
                            attributes),
                    EXTRACTION_ERROR,
                    ExceptionUtils.getStackTrace(e)
            );
            session.transfer(exFlowFile, REL_FAILURE);

            getLogger().error("An exception occurs during the QueryDatabaseToCSV processing", e);
        }
    }

    private boolean isRequestInvalid(FlowFile invocationFile, ProcessContext context) {
        return context.hasIncomingConnection() && invocationFile == null && context.hasNonLoopConnection();
    }

    private void removeInvocationFlowFile(FlowFile invocationFile, ProcessSession session) {
        if (invocationFile != null && !isWriteByBatch) {
            session.remove(invocationFile);
        }
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
        isWriteByBatch = context.getProperty(WRITE_BY_BATCH).asBoolean();
        batchSize = context.getProperty(BATCH_SIZE).asInteger();
        fetchSize = Integer.parseInt(context.getProperty(FETCH_SIZE).getValue());

        csvFormat = CSVFormat.valueOf(context.getProperty(CSV_FORMAT).getValue());
    }

    private Connection createConnection(ProcessContext context) {
        return getDbcpService(context).getConnection(Collections.emptyMap());
    }

    private PreparedStatement createPreparedStatement(Connection con) throws SQLException {
        PreparedStatement ps = con.prepareStatement(query);
        ps.setFetchSize(fetchSize);
        return ps;
    }

    private DBCPService getDbcpService(ProcessContext context) {
        return context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
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
