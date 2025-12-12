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

package org.qubership.nifi.reporting;

import io.prometheus.client.exporter.common.TextFormat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.mock.MockControllerServiceInitializationContext;
import org.apache.nifi.mock.MockReportingInitializationContext;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.util.MockConfigurationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SystemStubsExtension.class)
public class AbstractPrometheusReportingTaskProviderTest {

    private ReportingContext reportingContext;
    private TestPrometheusReportingTask task;
    private TestMeterRegistryProvider meterRegistryProvider;
    private static final String SERVER_URL = "http://localhost:9192/metrics";
    private OkHttpClient client;
    private String expectedHostname;

    @SystemStub
    private EnvironmentVariables environmentVariables;

    @BeforeEach
    public void setUp() throws Exception {
        environmentVariables.set("NAMESPACE", "local");
        task = new TestPrometheusReportingTask();
        meterRegistryProvider = new TestMeterRegistryProvider();
        ConfigurationContext csConfigurationContext = new MockConfigurationContext(
                new HashMap<PropertyDescriptor, String>(), null, null);
        ControllerServiceInitializationContext csInitContext = new MockControllerServiceInitializationContext();
        meterRegistryProvider.initialize(csInitContext);
        meterRegistryProvider.onScheduled(csConfigurationContext);
        //
        org.apache.nifi.util.MockControllerServiceLookup lookup = new TestMockControllerServiceLookup();
        lookup.addControllerService(meterRegistryProvider, "provider-id1");
        ConfigurationContext configurationContext =
                new MockConfigurationContext(initReportingTaskProperties(), lookup, null);
        task.initProperties();
        ReportingInitializationContext initializationContext = new MockReportingInitializationContext();
        task.initialize(initializationContext);
        task.onScheduled(configurationContext);
        //
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(5, TimeUnit.SECONDS);
        builder.readTimeout(5, TimeUnit.SECONDS);
        client = builder.build();

        expectedHostname = "cloud-data-migration-nifi";
        try {
            expectedHostname = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException ex) {
            expectedHostname = "cloud-data-migration-nifi";
        }
    }

    private Map<PropertyDescriptor, String> initReportingTaskProperties() {
        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(AbstractPrometheusReportingTask.METER_REGISTRY_PROVIDER, "provider-id1");
        return properties;
    }

    @AfterEach
    public void tearDown() throws Exception {
        task.onStopped();
        task.onShutDown();
        meterRegistryProvider.onStopped();
        meterRegistryProvider.onShutDown();
    }

    @Test
    public void registerMetrics() throws InitializationException, IOException {
        task.getMetricValue().setLeft(1.5);
        task.getMetricValue().setRight(2.5);
        task.onTrigger(reportingContext);
        //call endpoint 1st time:
        Request request = new Request.Builder().url(SERVER_URL).get().build();
        callMetricsEndpoint(request,
                List.of("test_metric_name002{tag1=\"tagValue1\",tag2=\"tagValue2\",} 2.5",
                        "test_metric_name001{hostname=\"" + expectedHostname + "\",instance=\"local_"
                                + expectedHostname + "\",namespace=\"local\","
                                + "tag1=\"tagValue1\",tag2=\"tagValue2\",} 1.5"));
        //change value:
        task.getMetricValue().setLeft(3.5);
        task.getMetricValue().setRight(5.5);
        //call endpoint 2nd time:
        callMetricsEndpoint(request,
                List.of("test_metric_name002{tag1=\"tagValue1\",tag2=\"tagValue2\",} 5.5",
                        "test_metric_name001{hostname=\"" + expectedHostname + "\",instance=\"local_"
                                + expectedHostname + "\",namespace=\"local\","
                                + "tag1=\"tagValue1\",tag2=\"tagValue2\",} 3.5"));
    }

    private void callMetricsEndpoint(Request request, List<String> expectedValues) throws IOException {
        try (Response resp = client.newCall(request).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : null;
            assertTrue(resp.isSuccessful());
            assertNotNull(responseBody);
            assertEquals(TextFormat.CONTENT_TYPE_004, resp.header("Content-Type"));
            System.out.println("responseBody = " + responseBody);
            if (expectedValues != null) {
                for (String expectedValue : expectedValues) {
                    assertTrue(responseBody.contains(expectedValue),
                            "Failed to find exact match for string = " + expectedValue);
                }
            }
        }
    }
}
