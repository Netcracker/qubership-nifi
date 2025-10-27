package org.qubership.nifi.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
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
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.ListRecordSet;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.serialization.record.util.DataTypeUtils;
import org.apache.nifi.util.StopWatch;
import com.fasterxml.jackson.databind.ObjectMapper;



import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SupportsBatching
//TODO: description, dynamic properties description
@Tags({"record", "put", "sink"})
@CapabilityDescription("")
//TODO: PutAttributesToRecord cnahge name
public class PutAttributesToMetric extends AbstractProcessor {

    private volatile RecordSinkService recordSinkService;
    private RecordSchema recordSchema;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final PropertyDescriptor RECORD_SINK = new PropertyDescriptor.Builder()
            .name("put-record-sink")
            .displayName("Record Destination Service")
            .description("Specifies the Controller Service to use for writing out the query result records to some destination.")
            .identifiesControllerService(RecordSinkService.class)
            .required(true)
            .build();

    public static final PropertyDescriptor SET_METRIC_TYPE = new PropertyDescriptor.Builder()
            .name("set-metric-type")
            .displayName("Set metric type")
            .description("")
            .required(true)
            .allowableValues("dynamicProperty", "staticJson")
            .defaultValue("dynamicProperty")
            .build();

    public static final PropertyDescriptor LIST_JSON_DYNAMIC_PROPERTY = new PropertyDescriptor.Builder()
            .name("list-json-dynamic-property")
            .displayName("")
            .description("")
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor STATIC_JSON = new PropertyDescriptor.Builder()
            .name("list-json-object")
            .displayName("")
            .description("")
            .dependsOn(SET_METRIC_TYPE, "staticJson")
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
        prop.add(SET_METRIC_TYPE);
        prop.add(LIST_JSON_DYNAMIC_PROPERTY);
        prop.add(STATIC_JSON);

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
                .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING, true))
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
        Map<String, Object> fieldValues = new HashMap<>();

        if ("dynamicProperty".equals(context.getProperty(SET_METRIC_TYPE).getValue())) {
            Map<String, String> listDynamicProperty = new HashMap<>();
            for (PropertyDescriptor propertyDescriptor : context.getProperties().keySet()) {
                if (propertyDescriptor.isDynamic()) {
                    listDynamicProperty.put(propertyDescriptor.getDisplayName(),
                            context.getProperty(propertyDescriptor)
                                    .evaluateAttributeExpressions(flowFile).getValue());
                }
            }

            //Schema generation
            try {
                recordSchema = generateSchemaDynamicProperty(listDynamicProperty, context);
            } catch (Exception exception) {

            }

            //generate recordSet
            fieldValues = generateRecordSetDynamicProperty(listDynamicProperty, context);
        }

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, fieldValues, true, true)
        ));

        try {
            WriteResult writeResult = recordSinkService.sendData(recordSet, new HashMap<>(flowFile.getAttributes()), true);
            String recordSinkURL = writeResult.getAttributes().get("record.sink.url");
            if (StringUtils.isEmpty(recordSinkURL)) {
                recordSinkURL = "unknown://";
            }
            final long transmissionMillis = stopWatch.getElapsed(TimeUnit.MILLISECONDS);
            if (writeResult.getRecordCount() > 0) {
                session.getProvenanceReporter().send(flowFile, recordSinkURL, transmissionMillis);
            }
        } catch (IOException exception) {
            getLogger().error("Error during transmission of records due to {}, routing to failure", exception.getMessage(), exception);
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        session.transfer(flowFile, REL_SUCCESS);
    }

    private Map<String, Object> generateRecordSetDynamicProperty(final Map<String, String> listDynamicProperty, final ProcessContext context) {
        Map<String, Object> fieldValues = new HashMap<>();

        if (context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue() == null) {
            for (RecordField recordField : recordSchema.getFields()){
                fieldValues.put(recordField.getFieldName(), listDynamicProperty.get(recordField.getFieldName()));
            }
        } else {
            //get list from properties
            List<String> jsonList = Arrays.stream(context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            //split json and nonjson
            Map<String, String> jsonDynamicPropertyValue = new HashMap<>();
            Map<String, String> nonJsonDynamicPropertyValue = new HashMap<>();
            for (String key : jsonList) {
                if (listDynamicProperty.containsKey(key)) {
                    jsonDynamicPropertyValue.put(key, listDynamicProperty.get(key));
                } else {
                    nonJsonDynamicPropertyValue.put(key, listDynamicProperty.get(key));
                }
            }
            //nonJson
            for (RecordField recordField : recordSchema.getFields()){
                fieldValues.put(recordField.getFieldName(), nonJsonDynamicPropertyValue.get(recordField.getFieldName()));
            }
            //json
            fieldValues.putAll(getJsonRecord(jsonDynamicPropertyValue));
        }

        return fieldValues;
    }

    private Map<String, Object> getJsonRecord(Map<String, String> jsonDynamicPropertyValue) {
        Map<String, Object> jsonFieldValues = new HashMap<>();

        jsonDynamicPropertyValue.forEach((propName, propValueJson) -> {
            try {
                JsonNode node = objectMapper.readTree(propValueJson);
                if (!node.isArray()) {
                    getJsonValue(node, jsonFieldValues);
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        return jsonFieldValues;
    }

    private void getJsonValue(JsonNode json, Map<String, Object> jsonFieldValues) {
        json.fields().forEachRemaining(jsonEntry -> {
            String jsonFieldName = jsonEntry.getKey();
            JsonNode jsonValue = jsonEntry.getValue();
            Optional<RecordField> schemaField = recordSchema.getField(jsonFieldName);
            if (RecordFieldType.DOUBLE.getDataType().equals(schemaField.get().getDataType())) {
                jsonFieldValues.put(schemaField.get().getFieldName(), jsonValue.asDouble());
            } else if (RecordFieldType.STRING.getDataType().equals(schemaField.get().getDataType())) {
                jsonFieldValues.put(schemaField.get().getFieldName(), jsonValue.asText());
            } else if (RecordFieldType.BOOLEAN.getDataType().equals(schemaField.get().getDataType())) {
                jsonFieldValues.put(schemaField.get().getFieldName(), jsonValue.asBoolean());
            }

            if (jsonValue.isObject()) {
                getJsonValue(jsonValue, jsonFieldValues);
            }
        });
    }

    private RecordSchema generateSchemaDynamicProperty(final Map<String, String> listDynamicProperty, final ProcessContext context) throws JsonProcessingException {
        List<RecordField> recordFields;

        if (context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue() == null) {
            recordFields = generateSimpleSchema(listDynamicProperty);
        } else {
            List<RecordField> recordFieldsJson;
            List<RecordField> recordFieldsNonJson;
            //get list from properties
            List<String> jsonList = Arrays.stream(context.getProperty(LIST_JSON_DYNAMIC_PROPERTY).getValue().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            //split json and nonjson
            Map<String, String> jsonDynamicProperty = new HashMap<>();
            Map<String, String> nonJsonDynamicProperty = new HashMap<>();
            for (String key : jsonList) {
                if (listDynamicProperty.containsKey(key)) {
                    jsonDynamicProperty.put(key, listDynamicProperty.get(key));
                } else {
                    nonJsonDynamicProperty.put(key, listDynamicProperty.get(key));
                }
            }
            recordFieldsJson = generateJsonSchema(jsonDynamicProperty);
            recordFieldsNonJson = generateSimpleSchema(nonJsonDynamicProperty);

            //combine to 1 list
            recordFields = Stream.concat(recordFieldsJson.stream(), recordFieldsNonJson.stream())
                    .collect(Collectors.toList());
        }

        return new SimpleRecordSchema(recordFields);
    }

    private RecordSchema generateSchemaStaticJson () {
        List<RecordField> recordFields = new ArrayList<>();




        return new SimpleRecordSchema(recordFields);
    }

    private List<RecordField> generateSimpleSchema(final Map<String, String> listDynamicProperty) {
        List<RecordField> simpleRecordField = new ArrayList<>();
        listDynamicProperty.forEach((key, value) -> {
            if (DataTypeUtils.isDoubleTypeCompatible(value)) {
                simpleRecordField.add(new RecordField(key, RecordFieldType.DOUBLE.getDataType()));
            } else {
                simpleRecordField.add(new RecordField(key, RecordFieldType.STRING.getDataType()));
            }
        });

        return simpleRecordField;
    }

    private List<RecordField> generateJsonSchema(final Map<String, String> listJsonDynamicProperty) {
        List<RecordField> jsonRecordField = new ArrayList<>();
        listJsonDynamicProperty.forEach((propertyName, json) -> {
            try {
                JsonNode node = objectMapper.readTree(json);
                //add check for array
                if (node.isArray()) {
                    //error
                }
                getType(node, jsonRecordField);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
        return jsonRecordField;
    }

    private void getType(JsonNode jsonObject, List<RecordField> jsonRecordField) {
        jsonObject.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode value = entry.getValue();
            //number
            if (value.isNumber()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.DOUBLE.getDataType()));
            //string
            } else if (value.isTextual()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.STRING.getDataType()));
            //boolean
            } else if (value.isBoolean()) {
                jsonRecordField.add(new RecordField(fieldName, RecordFieldType.BOOLEAN.getDataType()));
            //object
            } else if (value.isObject()) {
                getType(value, jsonRecordField);
            //array
            } else if (value.isArray()) {
                //error
            }
        });
    }

}
