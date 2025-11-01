package org.qubership.nifi.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
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
import java.util.stream.Collectors;

@SupportsBatching
//TODO: description, dynamic properties description
@Tags({"record", "put", "sink"})
@CapabilityDescription("")
public class PutRecordFromProperty extends AbstractProcessor {

    private volatile RecordSinkService recordSinkService;

    private boolean useDynamicProperty;
    private boolean jsonDynamicPropertyExist = false;

    private Set<String> jsonDynamicPropertySet;

    public static final AllowableValue DYNAMIC_PROPERTY = new AllowableValue("dynamicProperty",
            "Dynamic Property", "Get record from a dynamic properties");
    public static final AllowableValue STATIC_JSON = new AllowableValue("staticJson",
            "Static Json", "Get record from a static json property");

    private static final Validator LIST_JSON_DYNAMIC_PROPERTY_VALIDATOR = new Validator() {
        private final Validator LJDP_RE_VALIDATOR = StandardValidators.createRegexValidator(
                0,
                Integer.MAX_VALUE,
                true);
        @Override
        public ValidationResult validate(String subject, String input, ValidationContext context) {
            if (context.isExpressionLanguageSupported(subject) && context.isExpressionLanguagePresent(input)) {
                final AttributeExpression.ResultType resultType = context.newExpressionLanguageCompiler()
                        .getResultType(input);
                if (!resultType.equals(AttributeExpression.ResultType.STRING)) {
                    return new ValidationResult.Builder()
                            .subject(subject)
                            .input(input)
                            .valid(false)
                            .explanation("Expected property to to return type " + AttributeExpression.ResultType.STRING
                                    + " but expression returns type " + resultType)
                            .build();
                }
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(true)
                        .explanation("Property returns type " + AttributeExpression.ResultType.STRING)
                        .build();
            }

            return LJDP_RE_VALIDATOR.validate(subject, input, context);
        }
    };

    public static final PropertyDescriptor RECORD_SINK = new PropertyDescriptor.Builder()
            .name("put-record-sink")
            .displayName("Record Destination Service")
            .description("Specifies the Controller Service to use for writing out the query result records "
                    + "to some destination.")
            .identifiesControllerService(RecordSinkService.class)
            .required(true)
            .build();

    public static final PropertyDescriptor METRIC_TYPE = new PropertyDescriptor.Builder()
            .name("metric-type")
            .displayName("Metric type")
            .description("")
            .required(true)
            .allowableValues(DYNAMIC_PROPERTY, STATIC_JSON)
            .defaultValue("dynamicProperty")
            .build();

    public static final PropertyDescriptor LIST_JSON_DYNAMIC_PROPERTY = new PropertyDescriptor.Builder()
            .name("list-json-dynamic-property")
            .displayName("")
            .description("")
            .addValidator(LIST_JSON_DYNAMIC_PROPERTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor STATIC_JSON_OBJECT = new PropertyDescriptor.Builder()
            .name("")
            .displayName("")
            .description("")
            .dependsOn(METRIC_TYPE, "staticJson")
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
        prop.add(METRIC_TYPE);
        prop.add(LIST_JSON_DYNAMIC_PROPERTY);
        prop.add(STATIC_JSON_OBJECT);
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

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .required(false)
                .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(
                        AttributeExpression.ResultType.STRING, true))
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
    public void onScheduled(final ProcessContext context) throws JsonProcessingException {
        recordSinkService = context.getProperty(RECORD_SINK).asControllerService(RecordSinkService.class);

        useDynamicProperty = "dynamicProperty".equals(context.getProperty(METRIC_TYPE).getValue());

        if (context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue() != null) {
            jsonDynamicPropertyExist = true;
            jsonDynamicPropertySet = Arrays.stream(
                    context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue().split(","))
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
        final StopWatch stopWatch = new StopWatch(true);
        RecordSchema mainRecordSchema;
        Map<String, Object> fieldValues = new HashMap<>();
        List<RecordField> allFields = new ArrayList<>();
        RecordSet recordSet = null;

        if (useDynamicProperty) {
            Map<String, String> listDynamicProperty = new HashMap<>();
            try {
                for (PropertyDescriptor propertyDescriptor : context.getProperties().keySet()) {
                    if (propertyDescriptor.isDynamic()) {
                        listDynamicProperty.put(propertyDescriptor.getDisplayName(),
                                context.getProperty(propertyDescriptor)
                                        .evaluateAttributeExpressions(flowFile).getValue());
                    }
                }
            } catch (ProcessException e) {
                throw new ProcessException("An error occurred while evaluating attribute expressions.");
            }

            for (Map.Entry<String, String> entry : listDynamicProperty.entrySet()) {
                String dynamicPropertyName = entry.getKey();
                String dynamicPropertyValue = entry.getValue();
                if (jsonDynamicPropertyExist && jsonDynamicPropertySet.contains(dynamicPropertyName)) {
                    try {
                        JsonNode jsonValue = MAPPER.readTree(dynamicPropertyValue);
                        if (!jsonValue.isObject()) {
                            throw new IllegalArgumentException("Json must be an object");
                        }
                        List<RecordField> jsonRecordfield = new ArrayList<>();
                        Map<String, Object> nestedFieldValues = new HashMap<>();
                        jsonValue.fields().forEachRemaining( jsonField -> {
                            String fieldName = jsonField.getKey();
                            JsonNode value = jsonField.getValue();
                            if (value.isArray()) {
                                double[] doubles = new double[value.size()];
                                int i = 0;
                                for (JsonNode element : value) {
                                    if (!element.isNumber()) {
                                        throw new IllegalArgumentException("Array in Json must contain only elements of the numeric type.");
                                    }
                                    doubles[i++] = element.asDouble();
                                }
                                jsonRecordfield.add(new RecordField(fieldName, RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.DOUBLE.getDataType())));
                                nestedFieldValues.put(fieldName, doubles);
                            } else if (value.isObject()) {
                                throw new IllegalArgumentException("Json must not contain object");
                            } else if (value.isNumber()) {
                                jsonRecordfield.add(new RecordField(fieldName, RecordFieldType.DOUBLE.getDataType()));
                                nestedFieldValues.put(fieldName, value.asDouble());
                            } else if (value.isTextual()) {
                                jsonRecordfield.add(new RecordField(fieldName, RecordFieldType.STRING.getDataType()));
                                nestedFieldValues.put(fieldName, value.asText());
                            } else if (value.isBoolean()) {
                                jsonRecordfield.add(new RecordField(fieldName, RecordFieldType.BOOLEAN.getDataType()));
                                nestedFieldValues.put(fieldName, value.asBoolean());
                            }
                        });
                        RecordSchema nestedSchema = new SimpleRecordSchema(jsonRecordfield);
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
                        throw new RuntimeException(e);
                    }
                } else {
                    allFields.add(new RecordField(dynamicPropertyName,
                            DataTypeUtils.isDoubleTypeCompatible(dynamicPropertyValue) ?
                                    RecordFieldType.DOUBLE.getDataType() :
                                    RecordFieldType.STRING.getDataType())
                    );
                    fieldValues.put(dynamicPropertyName, listDynamicProperty.get(dynamicPropertyName));
                }
            }

            mainRecordSchema = new SimpleRecordSchema(allFields);
            recordSet = new ListRecordSet(mainRecordSchema, Arrays.asList(
                    new MapRecord(mainRecordSchema, fieldValues, true, true)
            ));
        } else {
            String staticJson = context.getProperty(STATIC_JSON_OBJECT).evaluateAttributeExpressions(flowFile).getValue();
            try {
                JsonNode staticJsonNode = MAPPER.readTree(staticJson);
                if (staticJsonNode.isArray()) {
                    throw new IllegalArgumentException("Static json cannot be an array");
                }
                staticJsonNode.fields().forEachRemaining( jsonNodeEntry -> {
                    String metricName = jsonNodeEntry.getKey();
                    JsonNode node = jsonNodeEntry.getValue();
                    if (!node.isObject()) {
                        throw new IllegalArgumentException("Static json cannot be an array");
                    }
                    if (!node.has("value") && !node.has("type")) {
                        throw new IllegalArgumentException("Static json must contain the value and type attributes.");
                    }
                    List<RecordField> staticJsonRecordfield = new ArrayList<>();
                    Map<String, Object> staticNestedFieldValues = new HashMap<>();
                    node.fields().forEachRemaining( childJsonObject -> {
                        String fieldName = childJsonObject.getKey();
                        JsonNode value = childJsonObject.getValue();
                        if (value.isArray()) {
                            Double[] doubles = new Double[value.size()];
                            int i = 0;
                            for (JsonNode element : value) {
                                if (!element.isNumber()) {
                                    throw new IllegalArgumentException("Array in Json must contain only elements of the numeric type.");
                                }
                                doubles[i++] = element.asDouble();
                            }
                            staticJsonRecordfield.add(new RecordField(fieldName, RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.DOUBLE.getDataType())));
                            staticNestedFieldValues.put(fieldName, doubles);
                        } else if (value.isObject()) {
                            throw new IllegalArgumentException("Json must not contain object");
                        } else if (value.isNumber()) {
                            staticJsonRecordfield.add(new RecordField(fieldName, RecordFieldType.DOUBLE.getDataType()));
                            staticNestedFieldValues.put(fieldName, value.asDouble());
                        } else if (value.isTextual()) {
                            staticJsonRecordfield.add(new RecordField(fieldName, RecordFieldType.STRING.getDataType()));
                            staticNestedFieldValues.put(fieldName, value.asText());
                        } else if (value.isBoolean()) {
                            staticJsonRecordfield.add(new RecordField(fieldName, RecordFieldType.BOOLEAN.getDataType()));
                            staticNestedFieldValues.put(fieldName, value.asBoolean());
                        }
                    });
                    RecordSchema nestedSchema = new SimpleRecordSchema(staticJsonRecordfield);
                    MapRecord jsonRecord = new MapRecord(
                            nestedSchema,
                            staticNestedFieldValues,
                            true,
                            true);
                    fieldValues.put(metricName, jsonRecord);
                    RecordField nestedRecordField = new RecordField(
                            metricName,
                            RecordFieldType.RECORD.getRecordDataType(nestedSchema)
                    );
                    allFields.add(nestedRecordField);
                });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            mainRecordSchema = new SimpleRecordSchema(allFields);
            recordSet = new ListRecordSet(mainRecordSchema, Arrays.asList(
                    new MapRecord(mainRecordSchema, fieldValues, true, true)
            ));
        }

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
            getLogger().warn("Error during transmission of records due to {}, routing to retry", rioe.getMessage(), rioe);
            session.transfer(flowFile, REL_RETRY);
            return;
        } catch (IOException exception) {
            getLogger().error("Error during transmission of records due to {}, routing to failure", exception.getMessage(), exception);
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        session.transfer(flowFile, REL_SUCCESS);
    }
}
