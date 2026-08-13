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

package org.qubership.nifi.tools.kb.docs;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.kb.cli.ExitCodes;
import org.qubership.nifi.tools.kb.cli.FailureClassifier;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuideCollectorTest {

    private MockWebServer server;
    private NiFiHttpClient httpClient;
    private NiFiUriResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        final CloseableHttpClient transport = NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(5));
        httpClient = new NiFiHttpClient(transport, resolver, NoAuthentication.INSTANCE);
    }

    @AfterEach
    void tearDown() throws IOException {
        httpClient.close();
        server.shutdown();
    }

    @Test
    void preservesUnauthorizedGuideStatus() {
        assertGuideFailure(401, ExitCodes.AUTH);
    }

    @Test
    void preservesForbiddenGuideStatus() {
        assertGuideFailure(403, ExitCodes.AUTHORIZATION);
    }

    private void assertGuideFailure(final int status, final int expectedExitCode) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("denied"));
        final GuideCollector collector = new GuideCollector(null, null);

        assertThatThrownBy(() -> collector.collect(httpClient, resolver, GuideMode.REQUIRED, "2.7.2"))
                .isInstanceOfSatisfying(NiFiApiException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(status);
                    assertThat(FailureClassifier.classify(failure)).isEqualTo(expectedExitCode);
                });
    }
}
