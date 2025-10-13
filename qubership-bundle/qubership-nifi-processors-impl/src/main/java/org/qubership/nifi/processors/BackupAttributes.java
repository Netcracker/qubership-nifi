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

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"Attribute", "BackupAttributes"})
@CapabilityDescription("Backups all FlowFile attributes by adding prefix to their names.")
public class BackupAttributes extends AbstractProcessor {

    /**
     * Success relationship.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully processed FlowFile")
            .build();

    /**
     * Prefix Attribute property descriptor.
     */
    public static final PropertyDescriptor PREFIX_ATTR = new PropertyDescriptor.Builder()
            .name("prefix-attr")
            .displayName("Prefix Attribute")
            .description("FlowFile attribute to use as prefix for backup attributes")
            .required(false)
            .addValidator(Validator.VALID)
            .sensitive(false)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * Excluded Attributes property descriptor.
     */
    public static final PropertyDescriptor EXCLUDED_ATTRS = new PropertyDescriptor.Builder()
            .name("excluded-attrs-regex")
            .displayName("Excluded Attributes")
            .description("Regular expression defining attributes to exclude from backup")
            .required(false)
            .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
            .sensitive(false)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    /**
     * List of all supported property descriptors.
     */
    protected List<PropertyDescriptor> descriptors;
    /**
     * Set of all supported relationships.
     */
    protected Set<Relationship> relationships;

    private String prefixAttr = "source.id";
    private Pattern excludedAttrsPattern = null;

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
        final List<PropertyDescriptor> descriptorsList = new ArrayList<>();
        descriptorsList.add(PREFIX_ATTR);
        descriptorsList.add(EXCLUDED_ATTRS);
        this.descriptors = Collections.unmodifiableList(descriptorsList);

        final Set<Relationship> relationshipList = new HashSet<>();
        relationshipList.add(REL_SUCCESS);
        this.relationships = Collections.unmodifiableSet(relationshipList);
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
     * This method will be called before any onTrigger calls and will be called once each time the Processor
     * is scheduled to run. This happens in one of two ways: either the user clicks to schedule the component to run,
     * or NiFi restarts with the "auto-resume state" configuration set to true (the default) and the component
     * is already running.
     *
     * @param context
     */
    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        prefixAttr = context.getProperty(PREFIX_ATTR).evaluateAttributeExpressions().getValue();
        String excludedAttrsStr = context.getProperty(EXCLUDED_ATTRS).evaluateAttributeExpressions().getValue();
        if (StringUtils.isNotEmpty(excludedAttrsStr)) {
            excludedAttrsPattern = Pattern.compile(excludedAttrsStr);
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

        Map<String, String> backupAttributes = new HashMap<>();
        String prefix = flowFile.getAttribute(prefixAttr);
        StringBuilder fullPrefix = new StringBuilder(prefix);
        //add delimiter:
        fullPrefix.append(".");

        for (Map.Entry<String, String> entry : flowFile.getAttributes().entrySet()) {
            //skip system attributes:
            String key = entry.getKey();
            if ((!"path".equals(key))
                && (!"uuid".equals(key))
                && (!"filename".equals(key))) {
                //skip excluded attributes, if defined:
                Matcher m = excludedAttrsPattern != null ? excludedAttrsPattern.matcher(key) : null;
                if (m == null || !m.matches()) {
                    StringBuilder newKey = new StringBuilder(fullPrefix);
                    newKey.append(key);
                    backupAttributes.put(newKey.toString(), entry.getValue());
                }
            }
        }
        //add new attributes:
        session.putAllAttributes(flowFile, backupAttributes);
        session.transfer(flowFile, REL_SUCCESS);
    }
}
