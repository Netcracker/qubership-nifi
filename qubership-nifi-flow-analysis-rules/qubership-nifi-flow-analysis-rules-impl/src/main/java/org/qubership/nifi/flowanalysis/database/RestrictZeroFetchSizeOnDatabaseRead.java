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

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flow.VersionedComponent;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flow.VersionedPropertyDescriptor;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.ComponentAnalysisResult;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;

/**
 * Flow analysis rule that reports a database-reading processor that has Fetch Size = 0. On PostgreSQL
 * and MySQL the JDBC driver then loads the whole result set into memory at once, which can cause an
 * OutOfMemoryError on large queries. Setting a positive value is safe on every database, so the rule
 * does not try to determine the database type.
 *
 * <p>The target processor is recognised by the presence of a property descriptor whose name or
 * display name is "Fetch Size", so custom JDBC processors following that convention are covered
 * without a hard-coded processor list.</p>
 */
@Tags({"processor", "database", "sql", "fetch size"})
@CapabilityDescription("Produces a rule violation for each database-reading processor that has "
        + "Fetch Size = 0. On PostgreSQL and MySQL the JDBC driver then loads the entire result set "
        + "into memory at once, which can cause an OutOfMemoryError on large queries.")
public final class RestrictZeroFetchSizeOnDatabaseRead extends AbstractFlowAnalysisRule {

    private static final String FETCH_SIZE_LABEL = "fetch size";
    private static final String VIOLATION_MESSAGE =
            "Fetch Size is 0, which may cause an OutOfMemoryError on large queries.";
    private static final String VIOLATION_EXPLANATION =
            "A Fetch Size of 0 lets the JDBC driver use its default. On PostgreSQL and MySQL the driver "
            + "then loads the whole result set into memory at once. Set a positive Fetch Size so the "
            + "driver streams the result set instead; a positive value is safe on every database.";

    @Override
    public Collection<ComponentAnalysisResult> analyzeComponent(
            final VersionedComponent component, final FlowAnalysisRuleContext context) {

        if (!(component instanceof final VersionedProcessor processor)) {
            return List.of();
        }
        final String fetchSizeProperty = fetchSizePropertyName(processor);
        if (fetchSizeProperty == null) {
            return List.of();
        }
        final Map<String, String> properties = processor.getProperties();
        if (properties == null || !"0".equals(properties.get(fetchSizeProperty))) {
            return List.of();
        }
        return List.of(new ComponentAnalysisResult("fetch-size-zero", VIOLATION_MESSAGE, VIOLATION_EXPLANATION));
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
}
