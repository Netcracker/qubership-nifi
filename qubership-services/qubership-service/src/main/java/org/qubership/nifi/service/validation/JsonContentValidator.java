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

package org.qubership.nifi.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.JsonUtils;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@CapabilityDescription("Provides validate method to check the JSON against a given schema.")
public class JsonContentValidator extends AbstractControllerService implements ContentValidator {

    public static final PropertyDescriptor SCHEMA = new PropertyDescriptor.Builder()
            .name("schema")
            .displayName("Schema")
            .description("Validation Json Schema.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    /**
     * Gets a list of supported properties.
     * @return list of supported property descriptors
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(SCHEMA);
        return properties;
    }

    private JsonSchema schemaInstance;

    /**
     * Configures controller service before use. This method is invoked when the controller service is enabled.
     * @param context configuration context to use
     * @throws InitializationException
     */
    @OnEnabled
    public void onConfigured(final ConfigurationContext context) throws InitializationException {
        String sSchema = context.getProperty(SCHEMA).evaluateAttributeExpressions().getValue();
        schemaInstance = JsonSchemaFactory.getInstance().getSchema(sSchema);
    }

    /**
     * Validates JSON against JSON Schema.
     * @param value input JSON
     * @param attributes map with FlowFile attributes
     * @return true, if valid. false, otherwise.
     * @throws IOException
     */
    @Override
    public boolean validate(String value, Map<String, String> attributes) throws IOException {
        JsonNode jsonValue = JsonUtils.MAPPER.readTree(value);
        Set<ValidationMessage> errors = schemaInstance.validate(jsonValue);

        return errors.isEmpty();
    }
}
