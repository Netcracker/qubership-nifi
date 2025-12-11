package org.qubership.nifi.processor.validation;


import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.qubership.nifi.NiFiUtils;
import org.qubership.nifi.service.validation.ContentValidator;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Content Validator processor.
 */
@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@CapabilityDescription("Validates content via supplied Content Validator Service.")
public class ContentValidatorProcessor
    extends AbstractProcessor {
    /**
     * Content Validator Service property descriptor.
     */
    public static final PropertyDescriptor CONTENT_VALIDATOR_SERVICE = new PropertyDescriptor.Builder()
            .name("Content Validator Service")
        .description("The Controller Service that is used to validate content.")
        .required(false)
        .identifiesControllerService(ContentValidator.class)
        .build();

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
        .description("FlowFiles that were successfully processed").build();

    /**
     * Failure relationship.
     */
    public static final  Relationship REL_FAILURE = new Relationship.Builder().name("failure")
        .description("FlowFiles that failed to process").build();

    private List<PropertyDescriptor> propertyDescriptors;
    private Set<Relationship> relationships;
    private ContentValidator validator;

    /**
     * Initializes the processor by setting up shared resources and configuration needed for creating
     * sessions during data processing. This method is called once by the framework when the processor
     * is first instantiated or loaded, and is responsible for performing one-time initialization tasks.
     *
     * @param context the initialization context providing access to controller services, configuration
     *  properties, and utility methods
     */
    @Override
    protected void init(ProcessorInitializationContext context) {
        super.init(context);
        this.propertyDescriptors = List.of(CONTENT_VALIDATOR_SERVICE);
        this.relationships = Set.of(REL_SUCCESS, REL_FAILURE);
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
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }


    /**
     * This method will be called before any onTrigger calls and will be called once each time the Processor
     * is scheduled to run. This happens in one of two ways: either the user clicks to schedule the component to run,
     * or NiFi restarts with the "auto-resume state" configuration set to true (the default) and the component
     * is already running.
     *
     * @param context process context
     */
    @OnScheduled
    public void onScheduled(ProcessContext context) {
        validator = context.getProperty(CONTENT_VALIDATOR_SERVICE).asControllerService(ContentValidator.class);
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
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
        String content = NiFiUtils.extractContent(session, flowFile);
        try {
            if (validator.validate(content, flowFile.getAttributes())) {
                //valid
                session.transfer(flowFile, REL_SUCCESS);
            } else {
                //invalid
                session.transfer(flowFile, REL_FAILURE);
            }
        } catch (IOException e) {
            getLogger().error("Failed to validate content", e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }
}
