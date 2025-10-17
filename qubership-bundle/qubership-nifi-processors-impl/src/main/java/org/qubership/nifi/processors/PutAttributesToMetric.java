package org.qubership.nifi.processors;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.ListRecordSet;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.util.StopWatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@SupportsBatching
@Tags({})
@CapabilityDescription("")
public class PutAttributesToMetric extends AbstractProcessor {

    public static final PropertyDescriptor RECORD_SINK = new PropertyDescriptor.Builder()
            .name("put-record-sink")
            .displayName("Record Destination Service")
            .description("Specifies the Controller Service to use for writing out the query result records to some destination.")
            .identifiesControllerService(RecordSinkService.class)
            .required(true)
            .build();

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("")
            .build();

    /**
     * Retry relationship.
     */
    static final Relationship REL_RETRY = new Relationship.Builder()
            .name("retry")
            .description("")
            .build();

    /**
     * Failure relationship.
     */
    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("")
            .build();

    /**
     * List of all supported property descriptors.
     */
    protected List<PropertyDescriptor> descriptors;
    private volatile RecordSinkService recordSinkService;
    private RecordSchema recordSchema;
    private String jsonPrefix="json";

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
    public void onScheduled(final ProcessContext context) {
        recordSinkService = context.getProperty(RECORD_SINK).asControllerService(RecordSinkService.class);

        List<RecordField> recordFields = new ArrayList<>();
        for (PropertyDescriptor descriptor : context.getProperties().keySet()) {
            if (descriptor.isDynamic()) {
                recordFields.add(new RecordField(descriptor.getDisplayName(), RecordFieldType.DOUBLE.getDataType()));
            }
        }
        recordSchema = new SimpleRecordSchema(recordFields);
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

        Map<String, Object> metricsList = new LinkedHashMap<>();
        for (RecordField recordField : recordSchema.getFields()){
            metricsList.put(recordField.getFieldName(), context.getProperty(recordField.getFieldName()).evaluateAttributeExpressions(flowFile).getValue());
        }


        final RecordReaderFactory recordParserFactory;

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, metricsList)
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
}
