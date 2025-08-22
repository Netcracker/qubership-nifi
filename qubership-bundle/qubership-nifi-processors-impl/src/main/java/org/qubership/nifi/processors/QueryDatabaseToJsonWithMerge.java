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
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.qubership.nifi.processors.json.JsonPathHelper;
import org.qubership.nifi.processors.json.context.InsertionContext;
import org.qubership.nifi.processors.json.context.JsonMergeContext;
import org.qubership.nifi.processors.json.exception.KeyNodeNotExistsException;
import org.qubership.nifi.processors.json.exception.NodeToInsertNotFoundException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.qubership.nifi.NiFiUtils.getEvaluatedValue;
import static org.qubership.nifi.NiFiUtils.readJsonNodeFromFlowFile;

@SideEffectFree
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"JSON", "DB"})
@CapabilityDescription("Executes custom query to fetch rows from table and merge them with the main JSON object which is in the content of incoming FlowFile. \n" +
        "The Path property supports JsonPath syntax to find source id attributes in the main object. The main and queried objects are merged by join key properties.\n" +
        "You can specify where exactly to insert queried objects and by what key with path to insert and key to insert properties.")
@WritesAttributes({
        @WritesAttribute(attribute = "mime.type", description = "Sets mime.type = application/json"),
        @WritesAttribute(attribute = "extraction.error", description = "Sets to error stacktrace, in case of exception")
})
public class QueryDatabaseToJsonWithMerge extends AbstractSingleQueryDatabaseToJson {


    public static final PropertyDescriptor SQL_QUERY = new PropertyDescriptor.Builder()
            .name("db-fetch-sql-query")
            .displayName("Query")
            .description("A custom SQL query used to retrieve data. Instead of building a SQL query from "
                    + "other properties, this query will be wrapped as a sub-query. Query must have no ORDER BY statement.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PATH = new PropertyDescriptor.Builder()
            .name("path")
            .displayName("Path")
            .description("A JsonPath expression that specifies path to source id attribute inside the array in the incoming FlowFile.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor JOIN_KEY_PARENT_WITH_CHILD = new PropertyDescriptor.Builder()
            .name("join-key-parent-with-child")
            .displayName("Join Key Parent With Child")
            .description("The objects' key in the input JSON that uses to join with objects that will be queried")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor JOIN_KEY_CHILD_WITH_PARENT = new PropertyDescriptor.Builder()
            .name("Join Key Child With Parent")
            .displayName("Join Key Child With Parent")
            .description("The queried objects' key that uses to join with objects that will come in the input JSON")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor KEY_TO_INSERT = new PropertyDescriptor.Builder()
            .name("key-to-insert")
            .displayName("Key To Insert")
            .description("A key that is used to insert the queried object in the main object.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor PATH_TO_INSERT = new PropertyDescriptor.Builder()
            .name("path-to-insert")
            .displayName("Path To Insert")
            .description("A key that uses to insert queried objects in the objects that will come in the input JSON")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor CLEAN_UP_POLICY = new PropertyDescriptor.Builder()
            .name("clean-up-policy")
            .displayName("Clean Up Policy")
            .description("Defines cleanup policy for keys used in join:\n" +
                    "- TARGET: remove parent key\n" +
                    "- SOURCE: remove child key\n" +
                    "- NONE: don't remove any keys\n")
            .required(false)
            .allowableValues("NONE", "SOURCE", "TARGET")
            .defaultValue("NONE")
            .build();

    private List<PropertyDescriptor> propDescriptors;

    public QueryDatabaseToJsonWithMerge() {
        final List<PropertyDescriptor> pds = new ArrayList<>(super.getSupportedPropertyDescriptors());
        pds.add(SQL_QUERY);
        pds.add(PATH);
        pds.add(JOIN_KEY_PARENT_WITH_CHILD);
        pds.add(JOIN_KEY_CHILD_WITH_PARENT);
        pds.add(KEY_TO_INSERT);
        pds.add(PATH_TO_INSERT);
        pds.add(CLEAN_UP_POLICY);

        this.propDescriptors = Collections.unmodifiableList(pds);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        FlowFile flowFile = session.get();

        if (flowFile == null) return;

        String path = getEvaluatedValue(PATH, context);
        String joinKeySourceWithTarget = getEvaluatedValue(JOIN_KEY_PARENT_WITH_CHILD, context, flowFile);
        String joinKeyTargetWithSource = getEvaluatedValue(JOIN_KEY_CHILD_WITH_PARENT, context, flowFile);
        String keyToInsert = context.getProperty(KEY_TO_INSERT).getValue();
        String pathToInsert = getEvaluatedValue(PATH_TO_INSERT, context);
        CleanUpPolicy cleanUpPolicy = CleanUpPolicy.valueOf(context.getProperty(CLEAN_UP_POLICY).getValue());

        JsonNode inputJson = readJsonNodeFromFlowFile(session, flowFile);
        JsonPathHelper jsonHelper = new JsonPathHelper(inputJson);

        InsertionContext insertionContext = InsertionContext
                .builder()
                .joinKeyParentWithChild(joinKeySourceWithTarget)
                .joinKeyChildWithParent(joinKeyTargetWithSource)
                .keyToInsert(keyToInsert)
                .isNeedToCleanTarget(
                        cleanUpPolicy == CleanUpPolicy.TARGET ||
                                cleanUpPolicy == CleanUpPolicy.BOTH
                )
                .build();

        try (
                Connection con = createConnection(context);
                PreparedStatement st = getStatementProducer().createPreparedStatement(
                        context.getProperty(SQL_QUERY).evaluateAttributeExpressions(flowFile).getValue(),
                        context,
                        jsonHelper.extractValuesByKey(path, joinKeySourceWithTarget),
                        con
                )
        ) {
            boolean originalAutocommit = con.getAutoCommit();
            try {
                con.setAutoCommit(false);
                st.setFetchSize(this.fetchSize);
                try (ResultSet resultSet = st.executeQuery()) {
                    getWriter()
                            .write(
                                    resultSet,
                                    batch -> {
                                        try {
                                            jsonHelper.merge(
                                                    JsonMergeContext
                                                            .builder()
                                                            .path(path)
                                                            .pathToInsert(pathToInsert)
                                                            .insertionContext(insertionContext)
                                                            .nodes((ArrayNode) batch)
                                                            .isArray(context.getProperty(BATCH_SIZE).asInteger() !=1)
                                                            .build()
                                            );
                                        } catch (NodeToInsertNotFoundException | KeyNodeNotExistsException e) {
                                            throw new ProcessException("There is an error to merge queried data with incoming", e);
                                        }
                                    },
                                    getLogger());
                }

                if (cleanUpPolicy == CleanUpPolicy.SOURCE || cleanUpPolicy == CleanUpPolicy.BOTH)
                    jsonHelper.cleanUp(path, joinKeySourceWithTarget);

                FlowFile ff = writeResultToFlowFile(jsonHelper.getJsonNode(), session, flowFile);
                session.putAttribute(ff, CoreAttributes.MIME_TYPE.key(), "application/json");
                session.transfer(ff,REL_SUCCESS);

                session.getProvenanceReporter().fetch(flowFile, con.getMetaData().getURL());
            } finally {
                try {
                    con.commit();
                } finally {
                    con.setAutoCommit(originalAutocommit);
                }
            }
        } catch (Exception e) {
            flowFile = session.putAttribute(flowFile, EXTRACTION_ERROR, ExceptionUtils.getStackTrace(e));
            session.transfer(flowFile, REL_FAILURE);

            getLogger().error("An exception occurs during the QueryDatabaseToJsonWithMerge processing", e);
        }
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }
}
