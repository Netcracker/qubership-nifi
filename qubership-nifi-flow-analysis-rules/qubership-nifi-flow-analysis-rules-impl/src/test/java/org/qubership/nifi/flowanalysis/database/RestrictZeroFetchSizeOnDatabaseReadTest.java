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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.nifi.flowanalysis.Fixtures.descriptor;
import static org.qubership.nifi.flowanalysis.Fixtures.processGroup;
import static org.qubership.nifi.flowanalysis.Fixtures.processor;
import static org.qubership.nifi.flowanalysis.Fixtures.setOf;

import java.util.Collection;
import java.util.Map;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RestrictZeroFetchSizeOnDatabaseReadTest {

    private final RestrictZeroFetchSizeOnDatabaseRead rule = new RestrictZeroFetchSizeOnDatabaseRead();
    private final FlowAnalysisRuleContext context = Mockito.mock(FlowAnalysisRuleContext.class);

    @Test
    public void reportsProcessorWithZeroFetchSize() {
        Collection<GroupAnalysisResult> results = analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "0"));

        assertEquals(1, results.size());
        GroupAnalysisResult result = results.iterator().next();
        assertEquals("p-1", result.getComponent().orElseThrow().getIdentifier());
        assertEquals("fetch-size-zero-p-1", result.getIssueId());
    }

    @Test
    public void messageExplainsTheRiskAndTheFix() {
        String message = analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "0"))
                .iterator().next().getMessage();

        assertTrue(message.contains("[p-1]"), message);
        assertTrue(message.contains("Fetch Size set to 0"), message);
        assertTrue(message.contains("OutOfMemoryError"), message);
        assertTrue(message.contains("Set Fetch Size to a positive value"), message);
    }

    @Test
    public void noViolationForPositiveFetchSize() {
        assertTrue(analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "1000")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeIsExpressionLanguage() {
        assertTrue(analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "${fetch.size}")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeIsParameterReference() {
        assertTrue(analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "#{fetch_size}")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeValueIsNotANumber() {
        assertTrue(analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "abc")).isEmpty());
    }

    @Test
    public void treatsZeroPaddedValueAsZero() {
        assertEquals(1, analyze(withFetchSize("p-1", "Fetch Size", "Fetch Size", "00")).size());
    }

    @Test
    public void detectsFetchSizeByDisplayNameWhenInternalNameDiffers() {
        assertEquals(1, analyze(withFetchSize("p-1", "fetch-size", "Fetch Size", "0")).size());
    }

    @Test
    public void detectsFetchSizeCaseInsensitively() {
        assertEquals(1, analyze(withFetchSize("p-1", "FETCH SIZE", "FETCH SIZE", "0")).size());
    }

    @Test
    public void noViolationWhenProcessorHasNoFetchSizeProperty() {
        VersionedProcessor processor = processor("p-1", "p-1",
                Map.of("Some Other", descriptor("Some Other", "Some Other")),
                Map.of("Some Other", "0"));

        assertTrue(analyze(processor).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeValueMissingFromProperties() {
        VersionedProcessor processor = processor("p-1", "p-1",
                Map.of("Fetch Size", descriptor("Fetch Size", "Fetch Size")),
                Map.of());

        assertTrue(analyze(processor).isEmpty());
    }

    @Test
    public void noNpeWhenPropertyDescriptorsNotSet() {
        assertTrue(analyze(processor("p-1", "p-1")).isEmpty());
    }

    @Test
    public void reportsOnlyProcessorsWithZeroFetchSize() {
        Collection<GroupAnalysisResult> results = analyze(
                withFetchSize("p-1", "Fetch Size", "Fetch Size", "0"),
                withFetchSize("p-2", "Fetch Size", "Fetch Size", "1000"));

        assertEquals(1, results.size());
        assertEquals("p-1", results.iterator().next().getComponent().orElseThrow().getIdentifier());
    }

    private Collection<GroupAnalysisResult> analyze(final VersionedProcessor... processors) {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processors));
        return rule.analyzeProcessGroup(group, context);
    }

    private static VersionedProcessor withFetchSize(final String id, final String propertyName,
                                                    final String displayName, final String value) {
        return processor(id, id,
                Map.of(propertyName, descriptor(propertyName, displayName)),
                value == null ? Map.of() : Map.of(propertyName, value));
    }
}
