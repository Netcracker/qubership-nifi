package org.qubership.nifi.service;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.util.ArrayList;
import java.util.List;

public class RedisTestProcessor extends AbstractProcessor {
    public static final PropertyDescriptor REDIS_MAP_CACHE_SERVICE = new PropertyDescriptor.Builder()
            .name("Redis-Map-Cache")
            .description("RedisBulkDistributedMapCacheClientService")
            .identifiesControllerService(RedisBulkDistributedMapCacheClientService.class)
            .required(true)
            .build();

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {

    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> propDescriptors = new ArrayList<>();
        propDescriptors.add(REDIS_MAP_CACHE_SERVICE);
        return propDescriptors;
    }
}
