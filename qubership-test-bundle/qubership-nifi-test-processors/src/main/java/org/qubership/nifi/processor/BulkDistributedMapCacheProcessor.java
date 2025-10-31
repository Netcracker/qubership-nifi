package org.qubership.nifi.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.exception.DeserializationException;
import org.apache.nifi.distributed.cache.client.exception.SerializationException;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.qubership.nifi.NiFiUtils;
import org.qubership.nifi.service.BulkDistributedMapCacheClient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BulkDistributedMapCacheProcessor
    extends AbstractProcessor {
    /**
     * Bulk Distributed Map Cache property descriptor.
     */
    public static final PropertyDescriptor BULK_DISTRIBUTED_MAP_CACHE = new PropertyDescriptor.Builder()
            .name("Bulk Distributed Map Cache")
            .description("The Controller Service that is used to perform bulk operation on distributed cache.")
            .required(false)
            .identifiesControllerService(BulkDistributedMapCacheClient.class)
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
    private static final String GET_AND_PUT_IF_ABSENT_OPERATION = "getAndPutIfAbsent";
    private static final String REMOVE_OPERATION = "remove";

    private List<PropertyDescriptor> propertyDescriptors;
    private Set<Relationship> relationships;
    private BulkDistributedMapCacheClient cache;
    private Serializer<String> stringSerializer;
    private Deserializer<String> stringDeserializer;
    private static final ObjectMapper MAPPER = NiFiUtils.MAPPER;

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
        this.propertyDescriptors = List.of(BULK_DISTRIBUTED_MAP_CACHE);
        this.relationships = Set.of(REL_SUCCESS, REL_FAILURE);
        this.stringSerializer = new StringSerializer();
        this.stringDeserializer = new StringDeserializer();
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
        cache = context.getProperty(BULK_DISTRIBUTED_MAP_CACHE).
                asControllerService(BulkDistributedMapCacheClient.class);
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
        JsonNode json = NiFiUtils.readJsonNodeFromFlowFile(session, flowFile);
        Map<String, String> getAndPutIfAbsentResult = null;
        //getAndPutIfAbsent
        if (json.has(GET_AND_PUT_IF_ABSENT_OPERATION)) {
            //getAndPutIfAbsent operation:
            JsonNode op = json.get(GET_AND_PUT_IF_ABSENT_OPERATION);
            Map<String, String> keysAndValues = new HashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = op.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                keysAndValues.put(entry.getKey(), entry.getValue().asText());
            }
            try {
                getAndPutIfAbsentResult = cache.getAndPutIfAbsent(keysAndValues,
                        stringSerializer, stringSerializer, stringDeserializer);
            } catch (IOException e) {
                getLogger().error("Failed to execute getAndPutIfAbsent", e);
            }
        }
        Long removeResult = null;
        //remove
        if (json.has(REMOVE_OPERATION)) {
            //remove operation:
            JsonNode op = json.get(REMOVE_OPERATION);
            ArrayNode removeArray = (ArrayNode) op;
            List<String> keysList = new ArrayList<>();
            for (JsonNode elem : removeArray) {
                keysList.add(elem.asText());
            }
            try {
                removeResult = cache.remove(keysList, stringSerializer);
            } catch (IOException e) {
                getLogger().error("Failed to execute getAndPutIfAbsent", e);
            }
        }
        //write result:
        ObjectNode rootNode = MAPPER.createObjectNode();
        boolean resultAdded = false;
        if (getAndPutIfAbsentResult != null) {
            //getAndPutIfAbsent done
            ObjectNode opNode = MAPPER.valueToTree(getAndPutIfAbsentResult);
            rootNode.putIfAbsent(GET_AND_PUT_IF_ABSENT_OPERATION, opNode);
            resultAdded = true;
        }
        if (removeResult != null) {
            //remove done
            rootNode.put(REMOVE_OPERATION, removeResult);
            resultAdded = true;
        }
        if (resultAdded) {
            session.write(flowFile, out -> NiFiUtils.MAPPER.writeValue(out, rootNode));
            session.transfer(flowFile, REL_SUCCESS);
        } else {
            session.transfer(flowFile, REL_FAILURE);
        }
    }

    private static final class StringSerializer implements Serializer<String> {
        @Override
        public void serialize(String s, OutputStream outputStream) throws SerializationException, IOException {
            if (s != null) {
                outputStream.write(s.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static final class StringDeserializer implements Deserializer<String> {
        @Override
        public String deserialize(byte[] input) throws DeserializationException, IOException {
            return input == null ? null : new String(input, StandardCharsets.UTF_8);
        }
    }
}
