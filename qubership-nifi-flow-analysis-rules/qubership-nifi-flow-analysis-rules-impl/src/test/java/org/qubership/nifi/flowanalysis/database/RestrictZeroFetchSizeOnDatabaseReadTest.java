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
import static org.qubership.nifi.flowanalysis.Fixtures.controllerService;
import static org.qubership.nifi.flowanalysis.Fixtures.descriptor;
import static org.qubership.nifi.flowanalysis.Fixtures.processor;

import java.util.Collection;
import java.util.Map;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.ComponentAnalysisResult;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RestrictZeroFetchSizeOnDatabaseReadTest {

    private final RestrictZeroFetchSizeOnDatabaseRead rule = new RestrictZeroFetchSizeOnDatabaseRead();
    private final FlowAnalysisRuleContext context = Mockito.mock(FlowAnalysisRuleContext.class);

    @Test
    public void reportsProcessorWithZeroFetchSize() {
        Collection<ComponentAnalysisResult> results = analyze(withFetchSize("Fetch Size", "Fetch Size", "0"));

        assertEquals(1, results.size());
        assertEquals("fetch-size-zero", results.iterator().next().getIssueId());
    }

    @Test
    public void messageIsShortAndExplanationHasTheDetails() {
        ComponentAnalysisResult result = analyze(withFetchSize("Fetch Size", "Fetch Size", "0")).iterator().next();

        assertTrue(result.getMessage().contains("Fetch Size is 0"), result.getMessage());
        assertTrue(result.getMessage().contains("OutOfMemoryError"), result.getMessage());
        assertTrue(result.getExplanation().contains("PostgreSQL and MySQL"), result.getExplanation());
        assertTrue(result.getExplanation().contains("Set a positive Fetch Size"), result.getExplanation());
    }

    @Test
    public void noViolationForPositiveFetchSize() {
        assertTrue(analyze(withFetchSize("Fetch Size", "Fetch Size", "1000")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeIsExpressionLanguage() {
        assertTrue(analyze(withFetchSize("Fetch Size", "Fetch Size", "${fetch.size}")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeIsParameterReference() {
        assertTrue(analyze(withFetchSize("Fetch Size", "Fetch Size", "#{fetch_size}")).isEmpty());
    }

    @Test
    public void noViolationWhenFetchSizeValueIsNotANumber() {
        assertTrue(analyze(withFetchSize("Fetch Size", "Fetch Size", "abc")).isEmpty());
    }

    @Test
    public void onlyExactlyZeroCountsAsZero() {
        assertTrue(analyze(withFetchSize("Fetch Size", "Fetch Size", "00")).isEmpty());
    }

    @Test
    public void detectsFetchSizeByDisplayNameWhenInternalNameDiffers() {
        assertEquals(1, analyze(withFetchSize("fetch-size", "Fetch Size", "0")).size());
    }

    @Test
    public void detectsFetchSizeCaseInsensitively() {
        assertEquals(1, analyze(withFetchSize("FETCH SIZE", "FETCH SIZE", "0")).size());
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
    public void ignoresNonProcessorComponents() {
        assertTrue(rule.analyzeComponent(controllerService("cs-1", "Pool"), context).isEmpty());
    }

    private Collection<ComponentAnalysisResult> analyze(final VersionedProcessor processor) {
        return rule.analyzeComponent(processor, context);
    }

    private static VersionedProcessor withFetchSize(final String propertyName,
                                                    final String displayName, final String value) {
        return processor("p-1", "p-1",
                Map.of(propertyName, descriptor(propertyName, displayName)),
                value == null ? Map.of() : Map.of(propertyName, value));
    }
}
