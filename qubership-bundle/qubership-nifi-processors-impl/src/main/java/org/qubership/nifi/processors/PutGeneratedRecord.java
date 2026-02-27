package org.qubership.nifi.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.DynamicProperties;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.record.sink.RetryableIOException;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.ListRecordSet;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.serialization.record.util.DataTypeUtils;
import org.apache.nifi.util.StopWatch;
import static org.qubership.nifi.NiFiUtils.MAPPER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@SupportsBatching
@Tags({"record", "put", "sink"})
@CapabilityDescription("A processor that generates Records based on its properties and sends them to a destination \n"
        + "specified by a Record Destination Service (i.e., record sink). The record source is defined by the \n"
        + "'Source Type' property, which can be either 'Dynamic Properties' or 'JSON Property'. If 'Source Type' \n"
        + "is set to 'Dynamic Properties', each dynamic property becomes a field in the Record, with the field type \n"
        + "automatically determined by the value type: string, double, or Record (if the dynamic property contains a \n"
        + "JSON value and is listed in the 'List JSON Dynamic Property' property). If 'Source Type' is set to \n"
        + "'JSON Property', the Record is generated directly from the JSON value in the 'JSON Property'.")
@DynamicProperties(@DynamicProperty(name = "*", value = "*",
        description = "The processor’s dynamic properties serve as the data source for generating a record. "
                + "The Dynamic Property Key defines the field name, while the value of the dynamic property determines"
                + " both the field type and its value. If the value type is numeric, the field type is set to double, "
                + "and the value is converted to double. If the value is in JSON format and the Dynamic Property Key "
                + "is listed in the 'List Json Dynamic Property' property, the field type is 'Record', with the schema"
                + " defined by the JSON structure. Otherwise, the field type is set to String. The JSON value must be"
                + " a single, flat JSON object, where the attributes can either be scalar values or arrays of numeric"
                + " values.",
        expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES))
public class PutGeneratedRecord extends AbstractProcessor {

    private final AtomicReference<RecordSinkService> recordSinkServiceAtomicReference = new AtomicReference<>();

    private boolean useDynamicProperty;
    private boolean jsonDynamicPropertyExist = false;

    private Set<String> jsonDynamicPropertySet;

    private static final Validator LIST_VALIDATOR = StandardValidators.createListValidator(
            true,
            true,
            StandardValidators.NON_EMPTY_VALIDATOR
    );

    private static final Validator DYNAMIC_PROPERTY_VALIDATOR = StandardValidators
            .createAttributeExpressionLanguageValidator(
            AttributeExpression.ResultType.STRING,
            true);

    /**
     * Record sink property descriptor.
     */
    public static final PropertyDescriptor RECORD_SINK = new PropertyDescriptor.Builder()
            .name("put-record-sink")
            .displayName("Record Destination Service")
            .description("The Controller Service which is used to send the result Record to some destination.")
            .identifiesControllerService(RecordSinkService.class)
            .required(true)
            .build();

    /**
     * Source type property descriptor.
     */
    public static final PropertyDescriptor SOURCE_TYPE = new PropertyDescriptor.Builder()
            .name("source-type")
            .displayName("Source type")
            .description("The source type that will be used to create the record. "
                    + "The record source can be a Dynamic Processor Property or a 'JSON Property' property.")
            .required(true)
            .allowableValues(
                    SourceTypeValues.DYNAMIC_PROPERTY.getAllowableValue(),
                    SourceTypeValues.JSON_PROPERTY.getAllowableValue()
            )
            .defaultValue(SourceTypeValues.DYNAMIC_PROPERTY.getAllowableValue())
            .build();

    /**
     * List json dynamic properties property descriptor.
     */
    public static final PropertyDescriptor LIST_JSON_DYNAMIC_PROPERTY = new PropertyDescriptor.Builder()
            .name("list-json-dynamic-property")
            .displayName("List JSON Dynamic Property")
            .description("Comma-separated list of dynamic properties that contain JSON values")
            .addValidator(LIST_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Json property descriptor.
     */
    public static final PropertyDescriptor JSON_PROPERTY_OBJECT = new PropertyDescriptor.Builder()
            .name("json-property-object")
                .displayName("JSON Property")
            .description("A complex JSON object for generating Record.A JSON object must have a flat structure without"
                    + " nested objects or arrays of non-scalar types. Object keys directly correspond to attribute"
                    + " names and are used as field names. All values must be scalar. Arrays containing only numeric"
                    + " values are allowed.")
            .dependsOn(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue())
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("The original FlowFile will be routed to this relationship if the records"
                    + "were transmitted successfully")
            .build();

    /**
     * Retry relationship.
     */
    public static final Relationship REL_RETRY = new Relationship.Builder()
            .name("retry")
            .description("The original FlowFile is routed to this relationship if the records could not be transmitted"
                    + "but attempting the operation again may succeed")
            .build();

    /**
     * Failure relationship.
     */
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship if the records could not be transmitted and"
                    + "retrying the operation will also fail")
            .build();

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
        final List<PropertyDescriptor> prop = new ArrayList<>();
        prop.add(RECORD_SINK);
        prop.add(SOURCE_TYPE);
        prop.add(LIST_JSON_DYNAMIC_PROPERTY);
        prop.add(JSON_PROPERTY_OBJECT);
        this.descriptors = Collections.unmodifiableList(prop);

        final Set<Relationship> rel = new HashSet<>();
        rel.add(REL_SUCCESS);
        rel.add(REL_RETRY);
        rel.add(REL_FAILURE);
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
     * Returns a dynamic PropertyDescriptors type that this component supports.
     * @param propertyDescriptorName name of property descriptor
     * @return PropertyDescriptor object this component currently supports
     */
    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .required(false)
                .addValidator(DYNAMIC_PROPERTY_VALIDATOR)
                .addValidator(StandardValidators.ATTRIBUTE_KEY_PROPERTY_NAME_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
                .dynamic(true)
                .build();
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
        recordSinkServiceAtomicReference.set(
                context.getProperty(RECORD_SINK).asControllerService(RecordSinkService.class)
        );

        useDynamicProperty = SourceTypeValues.DYNAMIC_PROPERTY.getAllowableValue().getValue()
                .equals(context.getProperty(SOURCE_TYPE).getValue());

        if (context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue() != null) {
            jsonDynamicPropertyExist = true;
            jsonDynamicPropertySet = Arrays.stream(
                    context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).evaluateAttributeExpressions()
                    .getValue().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
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
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
        RecordSinkService recordSinkService = recordSinkServiceAtomicReference.get();
        final StopWatch stopWatch = new StopWatch(true);
        RecordSchema mainRecordSchema;
        Map<String, Object> fieldValues = new HashMap<>();
        List<RecordField> allFields = new ArrayList<>();
        RecordSet recordSet = null;
        if (useDynamicProperty) {
            Map<String, String> dynamicPropertiesMap = new HashMap<>();
            try {
                for (PropertyDescriptor propertyDescriptor : context.getProperties().keySet()) {
                    if (propertyDescriptor.isDynamic()) {
                        dynamicPropertiesMap.put(propertyDescriptor.getDisplayName(),
                                context.getProperty(propertyDescriptor)
                                        .evaluateAttributeExpressions(flowFile).getValue());
                    }
                }
            } catch (ProcessException e) {
                throw new ProcessException("An error occurred while evaluating attribute expressions.");
            }

            for (Map.Entry<String, String> entry : dynamicPropertiesMap.entrySet()) {
                String dynamicPropertyName = entry.getKey();
                String dynamicPropertyValue = entry.getValue();
                if (jsonDynamicPropertyExist && jsonDynamicPropertySet.contains(dynamicPropertyName)) {
                    try {
                        JsonNode jsonValue = MAPPER.readTree(dynamicPropertyValue);
                        if (!jsonValue.isObject()) {
                            throw new ProcessException("Json specified in Dynamic Property - " + dynamicPropertyName
                                    + " must be an object.");
                        }
                        List<RecordField> jsonRecordField = new ArrayList<>();
                        Map<String, Object> nestedFieldValues = new HashMap<>();
                        String parentContext = "dynamic property " + dynamicPropertyName;
                        processJsonNode(jsonRecordField, nestedFieldValues, jsonValue, parentContext);
                        RecordSchema nestedSchema = new SimpleRecordSchema(jsonRecordField);
                        MapRecord jsonRecord = new MapRecord(
                                nestedSchema,
                                nestedFieldValues,
                                true,
                                true);
                        fieldValues.put(dynamicPropertyName, jsonRecord);
                        RecordField nestedRecordField = new RecordField(
                                dynamicPropertyName,
                                RecordFieldType.RECORD.getRecordDataType(nestedSchema)
                        );
                        allFields.add(nestedRecordField);
                    } catch (JsonProcessingException e) {
                        throw new ProcessException("An error occurred while parsing json for dynamic property - "
                                + dynamicPropertyName + ". Please check the provided json and try again. Error: "
                                + e.getMessage(), e);
                    }
                } else {
                    allFields.add(new RecordField(dynamicPropertyName,
                            DataTypeUtils.isDoubleTypeCompatible(dynamicPropertyValue)
                                    ? RecordFieldType.DOUBLE.getDataType()
                                    : RecordFieldType.STRING.getDataType())
                    );
                    fieldValues.put(dynamicPropertyName, dynamicPropertyValue);
                }
            }
            mainRecordSchema = new SimpleRecordSchema(allFields);
        } else {
            String staticJson = context.getProperty(JSON_PROPERTY_OBJECT)
                    .evaluateAttributeExpressions(flowFile).getValue();
            try {
                JsonNode staticJsonNode = MAPPER.readTree(staticJson);
                if (staticJsonNode.isArray()) {
                    throw new ProcessException("The Json specified in 'Json Property' cannot be an array.");
                }
                staticJsonNode.properties().forEach(jsonNodeEntry -> {
                    RecordField staticNestedRecordField;
                    String staticFieldName = jsonNodeEntry.getKey();
                    JsonNode staticValue = jsonNodeEntry.getValue();
                    if (staticValue.isObject()) {
                        List<RecordField> staticJsonRecordField = new ArrayList<>();
                        Map<String, Object> staticNestedFieldValues = new HashMap<>();
                        String parentContext = "attribute " + staticFieldName + " in Json Property";
                        processJsonNode(staticJsonRecordField, staticNestedFieldValues, staticValue, parentContext);
                        RecordSchema staticNestedSchema = new SimpleRecordSchema(staticJsonRecordField);
                        MapRecord staticJsonRecord = new MapRecord(
                                staticNestedSchema,
                                staticNestedFieldValues,
                                true,
                                true);
                        fieldValues.put(staticFieldName, staticJsonRecord);
                        staticNestedRecordField = new RecordField(
                                staticFieldName,
                                RecordFieldType.RECORD.getRecordDataType(staticNestedSchema)
                        );
                        allFields.add(staticNestedRecordField);
                    } else if (staticValue.isArray()) {
                        throw new ProcessException("The JSON values specified in 'Json Property' are invalid. "
                                + "The field " + staticFieldName + " cannot contain an array.");
                    } else {
                        if (staticValue.isNumber()) {
                            allFields.add(new RecordField(staticFieldName, RecordFieldType.DOUBLE.getDataType()));
                            fieldValues.put(staticFieldName, staticValue.asDouble());
                        } else {
                            allFields.add(new RecordField(staticFieldName, RecordFieldType.STRING.getDataType()));
                            fieldValues.put(staticFieldName, staticValue.asText());
                        }
                    }
                });
            } catch (JsonProcessingException e) {
                throw new ProcessException("An error occurred while parsing JSON values from 'Json Property'."
                        + "Please check the provided json and try again. Error message: " + e.getMessage(), e);
            }
            mainRecordSchema = new SimpleRecordSchema(allFields);
            if (getLogger().isDebugEnabled()) {
                getLogger().debug("Current RecordSchema: {}", mainRecordSchema.toString());
            }
        }
        recordSet = new ListRecordSet(mainRecordSchema, Arrays.asList(
                new MapRecord(mainRecordSchema, fieldValues, true, true)
        ));
        try {
            WriteResult writeResult = recordSinkService.sendData(
                    recordSet,
                    new HashMap<>(flowFile.getAttributes()),
                    true
            );
            String recordSinkURL = writeResult.getAttributes().get("record.sink.url");
            if (StringUtils.isEmpty(recordSinkURL)) {
                recordSinkURL = "unknown://";
            }
            final long transmissionMillis = stopWatch.getElapsed(TimeUnit.MILLISECONDS);
            if (writeResult.getRecordCount() > 0) {
                session.getProvenanceReporter().send(flowFile, recordSinkURL, transmissionMillis);
            }
        } catch (RetryableIOException rioe) {
            getLogger().warn("Error during transmission of records due to {},"
                    + " routing to retry", rioe.getMessage(), rioe);
            session.transfer(flowFile, REL_RETRY);
            return;
        } catch (IOException exception) {
            getLogger().error("Error during transmission of records due to {},"
                    + " routing to failure", exception.getMessage(), exception);
            session.transfer(flowFile, REL_FAILURE);
            return;
        }
        session.transfer(flowFile, REL_SUCCESS);
    }

    private void processJsonNode(
            List<RecordField> jsonRecordField,
            Map<String, Object> nestedFieldValues,
            JsonNode jsonValue,
            String parentContext) {
        jsonValue.properties().forEach(jsonField -> {
            String fieldName = jsonField.getKey();
            JsonNode value = jsonField.getValue();
            if (value.isArray()) {
                Double[] doubles = new Double[value.size()];
                int i = 0;
                for (JsonNode element : value) {
                    if (!element.isNumber()) {
                        throw new ProcessException("An array in JSON must contain only numeric elements."
                                + "The element " + element + " in the array " + value + "in the field " + fieldName
                                + " is not numeric.");
                    }
                    doubles[i++] = element.asDouble();
                }
                jsonRecordField.add(new RecordField(
                        fieldName, RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.DOUBLE.getDataType())));
                nestedFieldValues.put(fieldName, doubles);
            } else if (value.isObject()) {
                throw new ProcessException("Json must not contain nested objects. The field - "
                        + fieldName + " (within " + parentContext + ") is an object");
            } else if (value.isNumber()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.DOUBLE.getDataType()));
                nestedFieldValues.put(fieldName, value.asDouble());
            } else if (value.isTextual()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.STRING.getDataType()));
                nestedFieldValues.put(fieldName, value.asText());
            } else if (value.isBoolean()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.BOOLEAN.getDataType()));
                nestedFieldValues.put(fieldName, value.asBoolean());
            }
        });
    }

}
