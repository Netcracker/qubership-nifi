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

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;
import org.qubership.nifi.processors.json.JsonPathHelper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.qubership.nifi.NiFiUtils.getEvaluatedValue;
import static org.qubership.nifi.NiFiUtils.readJsonNodeFromFlowFile;

@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"JSON", "DB"})
@CapabilityDescription("Fetches data from database table and transforms it to JSON.\n"
        + "This processor gets incoming FlowFile and reads id attributes using Json Path. Found ids are passed\n"
        + "in select query as an array. Obtained result set will be written into output FlowFile.\n"
        + "Expects that content of an incoming FlowFile is array of unique business entity \n"
        + "identifiers in the JSON format.")
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "Sets mime.type = application/json"),
        @WritesAttribute(attribute = "extraction.error", description = "Sets to error stacktrace, in case of exception")
})

public class QueryDatabaseToJson extends AbstractSingleQueryDatabaseToJson {


    public static final PropertyDescriptor SQL_QUERY = new PropertyDescriptor.Builder()
            .name("db-fetch-sql-query")
            .displayName("Query")
            .description("A custom SQL query used to retrieve data. Instead of building a SQL query from other"
                    + " properties, this query will be wrapped as a sub-query. Query must have no ORDER BY statement.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PATH = new PropertyDescriptor.Builder()
            .name("path")
            .displayName("Path")
            .description("A JsonPath expression that specifies path to source id attribute inside the array "
                    + "in an incoming FlowFile.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    private List<PropertyDescriptor> propDescriptors;

    /**
     * Constructor for class QueryDatabaseToJson.
     */
    public QueryDatabaseToJson() {
        final List<PropertyDescriptor> pds = new ArrayList<>(super.getSupportedPropertyDescriptors());
        pds.add(SQL_QUERY);
        pds.add(PATH);

        this.propDescriptors = Collections.unmodifiableList(pds);
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
        FlowFile flowFile = session.get();

        if (flowFile == null) {
            return;
        }
        JsonPathHelper jsonPathHelper = new JsonPathHelper(readJsonNodeFromFlowFile(session, flowFile));
        List<String> id = jsonPathHelper.extractValuesByKey(getEvaluatedValue(PATH, context));

        try (
                Connection con = createConnection(context);
                PreparedStatement st = getStatementProducer().createPreparedStatement(
                        context.getProperty(SQL_QUERY).evaluateAttributeExpressions(flowFile).getValue(),
                        context,
                        id,
                        con
                );
        ) {
            String dbUrl = con.getMetaData().getURL();
            boolean originalAutocommit = con.getAutoCommit();
            try {
                //to allow PostgreSQL to use cursors, we have to set autocommit to false:
                con.setAutoCommit(false);
                st.setFetchSize(this.fetchSize);
                try (ResultSet resultSet = st.executeQuery()) {
                    getWriter().write(
                            resultSet,
                            batch -> {
                                FlowFile ff = writeResultToFlowFile(
                                        batch,
                                        session,
                                        session.create(flowFile)
                                );
                                session.getProvenanceReporter().fetch(ff, dbUrl);
                                session.putAttribute(ff, CoreAttributes.MIME_TYPE.key(), "application/json");
                                session.transfer(ff, REL_SUCCESS);
                            },
                            getLogger()
                    );
                }

                session.remove(flowFile);
            } finally {
                try {
                    con.commit();
                } finally {
                    con.setAutoCommit(originalAutocommit);
                }
            }
        } catch (Exception e) {
            FlowFile exFlowFile = session.putAttribute(flowFile, EXTRACTION_ERROR, ExceptionUtils.getStackTrace(e));
            session.transfer(exFlowFile, REL_FAILURE);

            getLogger().error("An exception occurs during the QueryDatabaseToJson processing", e);
        }
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
