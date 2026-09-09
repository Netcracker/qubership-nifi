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

package org.qubership.nifi.flowanalysis.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flow.VersionedPropertyDescriptor;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Flow analysis rule that reports a processor exposing a Fetch Size property set to 0. A Fetch Size
 * of 0 lets the JDBC driver use its default; on PostgreSQL and MySQL the driver then loads the whole
 * result set into memory at once, which can cause an OutOfMemoryError on large queries. Setting a
 * positive value is safe on every database, so the rule does not try to determine the database type.
 *
 * <p>The target processor is recognised by the presence of a property descriptor whose name or
 * display name is "Fetch Size", so custom JDBC processors following that convention are covered
 * without a hard-coded processor list.</p>
 */
@Tags({"processor", "database", "sql", "fetch size"})
@CapabilityDescription("Produces a rule violation for each processor that exposes a Fetch Size "
        + "property set to 0. A Fetch Size of 0 lets the JDBC driver use its default; on PostgreSQL "
        + "and MySQL the driver then loads the entire result set into memory at once, which can "
        + "cause an OutOfMemoryError on large queries.")
public final class RestrictZeroFetchSizeOnDatabaseRead extends AbstractFlowAnalysisRule {

    private static final String FETCH_SIZE_LABEL = "fetch size";

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {

        final List<GroupAnalysisResult> results = new ArrayList<>();
        for (final VersionedProcessor processor : processGroup.getProcessors()) {
            final String fetchSizeProperty = fetchSizePropertyName(processor);
            if (fetchSizeProperty == null) {
                continue;
            }
            final Map<String, String> properties = processor.getProperties();
            if (properties == null || !isZero(properties.get(fetchSizeProperty))) {
                continue;
            }
            results.add(GroupAnalysisResult
                    .forComponent(
                            processor,
                            "fetch-size-zero-" + processor.getIdentifier(),
                            buildMessage(processor))
                    .build());
        }
        return results;
    }

    private static String buildMessage(final VersionedProcessor processor) {
        return "The processor '" + processor.getName() + "' [" + processor.getIdentifier() + "] "
                + "reads from a database with Fetch Size set to 0, which lets the JDBC driver use its "
                + "default. On PostgreSQL and MySQL the driver then loads the entire result set into "
                + "memory at once, which can cause an OutOfMemoryError on large queries. Set Fetch Size "
                + "to a positive value (for example 1000) so the driver streams the result set.";
    }

    private static String fetchSizePropertyName(final VersionedProcessor processor) {
        final Map<String, VersionedPropertyDescriptor> descriptors = processor.getPropertyDescriptors();
        if (descriptors == null) {
            return null;
        }
        for (final VersionedPropertyDescriptor descriptor : descriptors.values()) {
            if (FETCH_SIZE_LABEL.equals(normalize(descriptor.getName()))
                    || FETCH_SIZE_LABEL.equals(normalize(descriptor.getDisplayName()))) {
                return descriptor.getName();
            }
        }
        return null;
    }

    private static String normalize(final String text) {
        if (text == null) {
            return null;
        }
        return text.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }

    private static boolean isZero(final String value) {
        if (value == null) {
            return false;
        }
        final String trimmed = value.trim();
        if (trimmed.contains("${") || trimmed.contains("#{")) {
            return false;
        }
        try {
            return Long.parseLong(trimmed) == 0L;
        } catch (final NumberFormatException e) {
            return false;
        }
    }
}
