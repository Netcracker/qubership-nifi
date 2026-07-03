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

import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"DBCP", "SQL", "COPY", "POSTGRESQL"})
@CapabilityDescription("The processor supports copying from stdin using the incoming content of the Flow File or a file accessible by path.\n" +
        "It is also possible to copy from DB to FlowFile content.")
@WritesAttributes({
        @WritesAttribute(attribute = "bulk.load.error", description = "If execution resulted in error, this attribute is populated with error message")
})
public class PostgreSQLBulkLoader extends AbstractProcessor {
    protected static final String ERROR_MSG_ATTR = "bulk.load.error";
    protected static final int DEFAULT_BUFFER_SIZE = 65536;

    public static final AllowableValue CONTENT = new AllowableValue("content", "Content", "FlowFile content");
    public static final AllowableValue FILE_SYSTEM = new AllowableValue("file-system", "File System", "File system");
    public static final AllowableValue FROM = new AllowableValue("from", "From", "Copy from stdin");
    public static final AllowableValue TO = new AllowableValue("to", "To", "Copy to stdout");

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully processed FlowFile.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship, if DB query failed with non-recoverable error.")
            .build();

    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("dbcp-service")
            .displayName("Database Connection Pooling Service")
            .description("Database Connection Pooling Service to use for connecting to target Database.")
            .required(true)
            .addValidator(Validator.VALID)
            .identifiesControllerService(DBCPService.class)
            .build();

    public static final PropertyDescriptor SQL_QUERY = new PropertyDescriptor.Builder()
            .name("sql-query")
            .displayName("SQL Query")
            .description("SQL query to execute. Copy command from stdin/to stdout.")
            .required(true)
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor FILE_PATH = new PropertyDescriptor.Builder()
            .name("file-path")
            .displayName("File Path")
            .description("Path to CSV file in file system.")
            .required(false)
            .addValidator(StandardValidators.URI_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor READ_FROM = new PropertyDescriptor.Builder()
            .name("read-from")
            .displayName("Read From")
            .description("Provides a selection of data to copy.")
            .required(false)
            .defaultValue(FILE_SYSTEM.getValue())
            .allowableValues(FILE_SYSTEM, CONTENT)
            .build();

    public static final PropertyDescriptor COPY_MODE = new PropertyDescriptor.Builder()
            .name("copy-mode")
            .displayName("Copy Mode")
            .description("Provides a selection of copy mode (from stdin/to stdout).")
            .required(true)
            .defaultValue(TO.getValue())
            .allowableValues(TO, FROM)
            .build();

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
    protected List<PropertyDescriptor> descriptors;
    protected Set<Relationship> relationships;

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

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        this.dbcp = context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
        this.bufferSize = context.getProperty(BUFFER_SIZE).isSet() ? context.getProperty(BUFFER_SIZE).asInteger() : DEFAULT_BUFFER_SIZE;
        this.isFromFS = FILE_SYSTEM.getValue().equals(context.getProperty(READ_FROM).getValue());
        this.copyMode = context.getProperty(COPY_MODE).getValue();
    }

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
                try (InputStream inputStream = isFromFS ? new BufferedInputStream(new FileInputStream(filePath)) : session.read(ff)) {
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
