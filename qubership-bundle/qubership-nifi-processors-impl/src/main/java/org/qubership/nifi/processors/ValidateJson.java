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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.nifi.processors.validation.JsonValidationHandler;
import org.qubership.nifi.processors.validation.ProcessorProperty;
import org.qubership.nifi.processors.validation.ValidationContext;
import org.qubership.nifi.processors.validator.JsonTreeValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayList;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static org.qubership.nifi.NiFiUtils.MAPPER;

@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"json", "schema", "validation"})
@CapabilityDescription("Validates the content of FlowFiles against the JSON schema. \n"
        + "The FlowFiles that are successfully validated against the specified schema are routed to valid \n"
        + "relationship without any changes. \n"
        + "The FlowFiles that are not valid according to the schema are routed to invalid relationship. \n"
        + "Array with validation errors is added to the content of FlowFile.")

public class ValidateJson extends AbstractProcessor {
    /**
     * Error FlowFile attribute name.
     */
    public static final String ERROR_ATTR = "validation.json.error";
    private static final ObjectMapper STRICT_MAPPER = MAPPER.enable(FAIL_ON_TRAILING_TOKENS);
    private static final String DEFAULT_BE_TYPE_PATH = "_businessEntityType";
    private static final String DEFAULT_ID_PATH = "_sourceId";
    private static final String DEFAULT_ERROR_CODE = "ME-JV-0002";
    private static final String SCHEMA_PROP_NAME = "validate-json-schema";
    private static final String SCHEMA_PROP_DISPLAY_NAME = "JSON Schema";

    /**
     * JSON Schema property descriptor.
     */
    public static final PropertyDescriptor SCHEMA = new PropertyDescriptor.Builder()
            .name(SCHEMA_PROP_NAME)
            .displayName(SCHEMA_PROP_DISPLAY_NAME)
            .description("Validation Json Schema")
            .required(true)
            .addValidator(new JsonTreeValidator(MAPPER))
            .build();

    private static final String BE_TYPE_PATH_PROP_NAME = "be-type-path";
    private static final String BE_TYPE_PATH_PROP_DISPLAY_NAME = "Entity Type Path";
    /**
     * Entity Type Path property descriptor.
     */
    public static final PropertyDescriptor BE_TYPE_PATH_PROP = new PropertyDescriptor.Builder()
            .name(BE_TYPE_PATH_PROP_NAME)
            .displayName(BE_TYPE_PATH_PROP_DISPLAY_NAME)
            .description("A JsonPath expression that specifies path to business entity type "
                    + "attribute in the content of incoming FlowFile.")
            .required(true)
            .defaultValue(DEFAULT_BE_TYPE_PATH)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();

    private static final String ID_PATH_PROP_NAME = "source-id-path";
    private static final String ID_PATH_PROP_DISPLAY_NAME = "ID Path";
    /**
     * ID Path property descriptor.
     */
    public static final PropertyDescriptor ID_PATH_PROP = new PropertyDescriptor.Builder()
            .name(ID_PATH_PROP_NAME)
            .displayName(ID_PATH_PROP_DISPLAY_NAME)
            .description("A JsonPath expression that specifies path to source id attribute"
                    + " in the content of incoming FlowFile.")
            .required(true)
            .defaultValue(DEFAULT_ID_PATH)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();

    private static final String ERROR_CODE_PROP_NAME = "error-code";
    private static final String ERROR_CODE_PROP_DISPLAY_NAME = "Error Code";
    /**
     * Error Code property descriptor.
     */
    public static final PropertyDescriptor ERROR_CODE_PROP = new PropertyDescriptor.Builder()
            .name(ERROR_CODE_PROP_NAME)
            .displayName(ERROR_CODE_PROP_DISPLAY_NAME)
            .description("Validation error code. Used as identification error code when"
                    + " formatting an array of validation errors.")
            .required(true)
            .defaultValue(DEFAULT_ERROR_CODE)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();

    private static final String WRAPPER_REGEX_PROP_NAME = "wrapper-regex";
    private static final String WRAPPER_REGEX_PROP_DISPLAY_NAME = "Wrapper regex";
    /**
     * Wrapper regex property descriptor.
     */
    public static final PropertyDescriptor WRAPPER_REGEX = new PropertyDescriptor.Builder()
            .name(WRAPPER_REGEX_PROP_NAME)
            .displayName(WRAPPER_REGEX_PROP_DISPLAY_NAME)
            .description("Regex to define path of wrapper in aggregated business entity. "
                    + "If validation errors are detected and regex is set and matched, "
                    + "the wrapper path will be removed from the error path,"
                    + " ID of the wrapper will be replaced to ID of the business entity.")
            .required(false)
            .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
            .build();

    private static final String REL_VALID_NAME = "valid";
    /**
     * Valid relationship.
     */
    public static final Relationship REL_VALID = new Relationship.Builder()
            .name(REL_VALID_NAME)
            .description("FlowFiles matching the specified schema are routed to this relationship.")
            .build();

    private static final String REL_INVALID_NAME = "invalid";
    /**
     * Invalid relationship.
     */
    public static final Relationship REL_INVALID = new Relationship.Builder()
            .name(REL_INVALID_NAME)
            .description("FlowFiles not matching the specified schema are routed to this relationship.")
            .build();
    private static final String REL_NOT_JSON_NAME = "not_json";
    /**
     * Not JSON relationship.
     */
    public static final Relationship REL_NOT_JSON = new Relationship.Builder()
            .name(REL_NOT_JSON_NAME)
            .description("FlowFiles that are not valid JSON are routed to this relationship")
            .autoTerminateDefault(true)
            .build();

    private List<PropertyDescriptor> properties;
    private Set<Relationship> relationships;
    private ProcessorProperty processorProperty;

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
        final List<PropertyDescriptor> prop = new ArrayList<>();
        prop.add(SCHEMA);
        prop.add(BE_TYPE_PATH_PROP);
        prop.add(ID_PATH_PROP);
        prop.add(ERROR_CODE_PROP);
        prop.add(WRAPPER_REGEX);
        this.properties = Collections.unmodifiableList(prop);

        final Set<Relationship> rel = new HashSet<>();
        rel.add(REL_VALID);
        rel.add(REL_INVALID);
        rel.add(REL_NOT_JSON);
        this.relationships = Collections.unmodifiableSet(rel);
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
        return properties;
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
        String sSchema = context.getProperty(SCHEMA).getValue();
        JsonSchema schema = JsonSchemaFactory.getInstance().getSchema(sSchema);

        String beTypePath = context.getProperty(BE_TYPE_PATH_PROP_NAME).getValue();
        String idPath = context.getProperty(ID_PATH_PROP_NAME).getValue();
        String errorCode = context.getProperty(ERROR_CODE_PROP_NAME).getValue();
        String wrapperRegEx = context.getProperty(WRAPPER_REGEX).getValue();

        this.processorProperty = new ProcessorProperty(beTypePath, idPath, errorCode, schema, wrapperRegEx);
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
        flowFile = session.putAttribute(flowFile, "migration.phase", "Validation");
        JsonNode jsonNode = extractJsonNodeFromFlowFile(session, flowFile);
        if (jsonNode == null) {
            session.transfer(flowFile, REL_NOT_JSON);
            return;
        }
        JsonValidationHandler validationHandler = new JsonValidationHandler(jsonNode, processorProperty, getLogger());

        ValidationContext validationContext = validationHandler.process();

        if (validationContext.isValidJson()) {
            session.transfer(flowFile, REL_VALID);
            session.getProvenanceReporter().route(flowFile, REL_VALID);
        } else {
            session.write(flowFile, outputStream -> outputStream.write(
                    validationContext
                            .getValidatedJson()
                            .toString()
                            .getBytes(StandardCharsets.UTF_8)
                    )
            );
            session.putAttribute(flowFile, "processor.uuid", getIdentifier());
            session.transfer(flowFile, REL_INVALID);
        }
    }

    private JsonNode extractJsonNodeFromFlowFile(ProcessSession session, FlowFile inputFlowFile) {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        try {
            session.read(inputFlowFile, in -> result.set(STRICT_MAPPER.readTree(in)));
            return result.get();
        } catch (Exception e) {
            session.putAttribute(inputFlowFile, ERROR_ATTR, "Not json content in Flow file: "
                    + ((e.getCause() != null) ? e.getCause() : e).getMessage());
            getLogger().error("Not json content in Flow file", e);
            return null;
        }
    }
}
