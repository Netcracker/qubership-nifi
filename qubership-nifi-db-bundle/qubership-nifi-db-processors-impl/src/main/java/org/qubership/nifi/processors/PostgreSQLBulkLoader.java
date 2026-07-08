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

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"DBCP", "SQL", "COPY", "POSTGRESQL"})
@CapabilityDescription("The processor supports copying from stdin using the incoming content of the Flow File or "
        + "a file accessible by path.\n"
        + "It is also possible to copy from DB to FlowFile content.")
@WritesAttributes({
        @WritesAttribute(attribute = "bulk.load.error", description = "If execution resulted in error, this attribute"
                + " is populated with error message")
})
public class PostgreSQLBulkLoader extends AbstractProcessor {
    /**
     * Error message FlowFile attribute name.
     */
    protected static final String ERROR_MSG_ATTR = "bulk.load.error";

    /**
     * Default buffer size.
     */
    protected static final int DEFAULT_BUFFER_SIZE = 65536;

    /**
     * Content.
     */
    public static final AllowableValue CONTENT = new AllowableValue("content", "Content", "FlowFile content");
    /**
     * File system.
     */
    public static final AllowableValue FILE_SYSTEM = new AllowableValue("file-system", "File System", "File system");
    /**
     * From.
     */
    public static final AllowableValue FROM = new AllowableValue("from", "From", "Copy from stdin");
    /**
     * To.
     */
    public static final AllowableValue TO = new AllowableValue("to", "To", "Copy to stdout");

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully processed FlowFile.")
            .build();

    /**
     * Failure relationship.
     */
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship, if DB query failed with non-recoverable error.")
            .build();

    /**
     * DBCP Service descriptor.
     */
    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("dbcp-service")
            .displayName("Database Connection Pooling Service")
            .description("Database Connection Pooling Service to use for connecting to target Database.")
            .required(true)
            .addValidator(Validator.VALID)
            .identifiesControllerService(DBCPService.class)
            .build();

    /**
     * Sql Query property descriptor.
     */
    public static final PropertyDescriptor SQL_QUERY = new PropertyDescriptor.Builder()
            .name("sql-query")
            .displayName("SQL Query")
            .description("SQL query to execute. Copy command from stdin/to stdout.")
            .required(true)
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * File Path property descriptor.
     */
    public static final PropertyDescriptor FILE_PATH = new PropertyDescriptor.Builder()
            .name("file-path")
            .displayName("File Path")
            .description("Path to CSV file in file system.")
            .required(false)
            .addValidator(StandardValidators.URI_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Read From property descriptor.
     */
    public static final PropertyDescriptor READ_FROM = new PropertyDescriptor.Builder()
            .name("read-from")
            .displayName("Read From")
            .description("Provides a selection of data to copy.")
            .required(false)
            .defaultValue(FILE_SYSTEM.getValue())
            .allowableValues(FILE_SYSTEM, CONTENT)
            .build();

    /**
     * Copy Mode property descriptor.
     */
    public static final PropertyDescriptor COPY_MODE = new PropertyDescriptor.Builder()
            .name("copy-mode")
            .displayName("Copy Mode")
            .description("Provides a selection of copy mode (from stdin/to stdout).")
            .required(true)
            .defaultValue(TO.getValue())
            .allowableValues(TO, FROM)
            .build();

    /**
     * Buffer Size property descriptor.
     */
    public static final PropertyDescriptor BUFFER_SIZE = new PropertyDescriptor.Builder()
            .name("buffer-size")
            .displayName("Buffer Size")
            .description("Number of characters to buffer and push over network to server at once.")
            .required(false)
            .addValidator(Validator.VALID)
            .build();

    private DBCPService dbcp;
    private int bufferSize;
    private boolean isFromFS;
    private String copyMode;
    /**
     * List of all supported property descriptors.
     */
    protected List<PropertyDescriptor> descriptors;
    /**
     * Set of all supported relationships.
     */
    protected Set<Relationship> relationships;

    /**
     * Initializes the processor by setting up shared resources and configuration needed for creating
     * sessions during data processing. This method is called once by the framework when the processor
     * is first instantiated or loaded, and is responsible for performing one-time initialization tasks.
     *
     * @param context the initialization context providing access to controller services, configuration
     *  properties, and utility methods
     */
    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptorsList = new ArrayList<>();
        descriptorsList.add(DBCP_SERVICE);
        descriptorsList.add(SQL_QUERY);
        descriptorsList.add(FILE_PATH);
        descriptorsList.add(BUFFER_SIZE);
        descriptorsList.add(COPY_MODE);
        descriptorsList.add(READ_FROM);
        this.descriptors = Collections.unmodifiableList(descriptorsList);

        final Set<Relationship> relationshipList = new HashSet<>();
        relationshipList.add(REL_SUCCESS);

        relationshipList.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationshipList);
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
        return this.relationships;
    }

    /**
     * Returns a List of all PropertyDescriptors that this component supports.
     * Returns:
     * PropertyDescriptor objects this component currently supports
     *
     */
    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
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
    public void onScheduled(final ProcessContext context) {
        this.dbcp = context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
        this.bufferSize = context.getProperty(BUFFER_SIZE).isSet()
                ? context.getProperty(BUFFER_SIZE).asInteger() : DEFAULT_BUFFER_SIZE;
        this.isFromFS = FILE_SYSTEM.getValue().equals(context.getProperty(READ_FROM).getValue());
        this.copyMode = context.getProperty(COPY_MODE).getValue();
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

        FlowFile ff = session.get();
        if (ff == null) {
            return;
        }

        boolean withoutErrors = true;

        String filePath = context.getProperty(FILE_PATH).evaluateAttributeExpressions(ff).getValue();
        String sqlQuery = context.getProperty(SQL_QUERY).evaluateAttributeExpressions(ff).getValue();

        try (Connection con = dbcp.getConnection()) {
            CopyManager copyManager = new CopyManager((BaseConnection) con.unwrap(PGConnection.class));

            if (FROM.getValue().equals(copyMode)) {
                try (InputStream inputStream = isFromFS
                        ? new BufferedInputStream(new FileInputStream(filePath)) : session.read(ff)) {
                    copyManager.copyIn(sqlQuery, inputStream, bufferSize);
                } catch (IOException e) {
                    withoutErrors = false;
                    session.putAttribute(ff, ERROR_MSG_ATTR, e.getMessage());
                    session.transfer(ff, REL_FAILURE);
                    if (isFromFS) {
                        getLogger().error("Cannot read file {}", new Object[]{filePath}, e);
                    } else {
                        getLogger().error("Cannot read content", e);
                    }
                }
                session.getProvenanceReporter().send(ff, con.getMetaData().getURL());
            } else {
                try (OutputStream outputStream = session.write(ff)) {
                    copyManager.copyOut(sqlQuery, outputStream);
                } catch (IOException e) {
                    withoutErrors = false;
                    session.putAttribute(ff, ERROR_MSG_ATTR, e.getMessage());
                    session.transfer(ff, REL_FAILURE);
                    getLogger().error("Cannot write to content", e);
                }
                session.getProvenanceReporter().fetch(ff, con.getMetaData().getURL());
            }
            if (withoutErrors) {
                session.transfer(ff, REL_SUCCESS);
            }
        } catch (SQLException e) {
            session.putAttribute(ff, ERROR_MSG_ATTR, e.getMessage());
            session.transfer(ff, REL_FAILURE);
            getLogger().error("Cannot execute query: {}", new Object[]{sqlQuery}, e);
        }
    }
}
