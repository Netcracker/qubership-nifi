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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.nifi.tools.kb.collect.CollectionException;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComponentDocumentationCollectorTest {
    private MockWebServer server;
    private NiFiHttpClient http;
    private ComponentDocumentationCollector collector;
    private final NiFiComponentReference reference = new NiFiComponentReference(NiFiComponentKind.PROCESSOR,
            "org.example", "bundle space", "1.28.1", "org.example.UpdateAttribute");

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        var resolver = NiFiUriResolver.fromBaseUrl(server.url("/prefix/nifi").toString(), false);
        http = new NiFiHttpClient(NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(2)), resolver,
                NoAuthentication.INSTANCE, new NiFiHttpClient.Config(Duration.ofSeconds(2), 1024 * 1024,
                0, Duration.ofMillis(1), Duration.ofMillis(1), 3));
        collector = new ComponentDocumentationCollector(http, resolver);
    }

    @AfterEach
    void teardown() throws Exception {
        http.close();
        server.close();
    }

    @Test
    void convertsCompletePageAndStructuredMetadataAndCachesTheProbe() throws Exception {
        server.enqueue(html(fixture()));
        server.enqueue(html("<html><body><h2>Details</h2><p>Preserve &#233; and code.</p></body></html>"));
        var page = collector.collect(reference);
        assertThat(page.markdown()).contains("State management", "Dynamic Properties", "Writes Attributes");
        assertThat(page.metadata().path("writesAttributes").get(0).path("name").asText()).isEqualTo("example");
        assertThat(page.additionalMarkdown()).contains("\u00e9");
        assertThat(page.additionalState().isAvailable()).isTrue();
        assertThat(collector.collect(reference)).isSameAs(page);
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(server.takeRequest().getPath())
                .contains("/prefix/nifi-docs/components/org.example/bundle%20space/1.28.1/");
    }

    @Test
    void distinguishesNotAdvertisedNotFoundAndBlankAdditionalDetails() throws Exception {
        server.enqueue(html(fixture().replace("<a href=\"additionalDetails.html\">Additional Details...</a>", "")));
        assertThat(collector.collect(reference).additionalState().isAdvertised()).isFalse();
        for (String version : new String[]{"1.28.2", "1.28.3"}) {
            server.enqueue(html(fixture()));
            server.enqueue(version.endsWith("2") ? new MockResponse().setResponseCode(404) : html(" "));
            var page = collector.collect(new NiFiComponentReference(reference.kind(), reference.group(),
                    reference.artifact(), version, reference.type()));
            assertThat(page.additionalState().isAdvertised()).isTrue();
            assertThat(page.additionalState().isRequested()).isTrue();
            assertThat(page.additionalState().isAvailable()).isFalse();
        }
    }

    @Test
    void rejectsMissingAndMalformedPages() {
        for (String version : new String[]{"1", "2", "3"}) {
            server.enqueue(version.equals("1") ? new MockResponse().setResponseCode(404)
                    : html(version.equals("2") ? "<html><body>NiFi login</body></html>" : "broken"));
            assertThatThrownBy(() -> collector.collect(new NiFiComponentReference(reference.kind(),
                    reference.group(), reference.artifact(), version, reference.type())))
                    .hasMessageContaining("/nifi-docs/components/");
        }
    }

    @Test
    void rejectsUnsafeLinksAndRedirectsBeforeFollowingThem() throws Exception {
        for (String link : new String[]{"https://elsewhere.invalid/details.html", "../../escape.html",
                "%2e%2e/escape.html"}) {
            server.enqueue(html(fixture().replace("additionalDetails.html", link)));
            assertThatThrownBy(() -> collector.collect(reference)).isInstanceOf(CollectionException.class);
        }
        server.enqueue(html(fixture()));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/prefix/nifi-api/flow/about"));
        assertThatThrownBy(() -> collector.collect(reference)).hasMessageContaining("Rejected redirect");
        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    @Test
    void advertisedAuthenticationServerAndMalformedHtmlFailuresAreFatal() throws Exception {
        for (int status : new int[]{401, 403, 500, 200}) {
            server.enqueue(html(fixture()));
            server.enqueue(html("<html><body><form><input type=password></form></body></html>")
                    .setResponseCode(status));
            assertThatThrownBy(() -> collector.collect(reference)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void additionalDetailsWithAnInteractiveFormAreAccepted() throws Exception {
        server.enqueue(html(fixture()));
        server.enqueue(html("<html><body><h2>Try it</h2><form><input type=text name=q></form></body></html>"));
        var page = collector.collect(reference);
        assertThat(page.additionalMarkdown()).contains("Try it");
    }

    /**
     * Each page has visible text, so only the sign-in marker it carries can reject it.
     *
     * @param signInMarkup the markup that marks the page as a sign-in page
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "<form action=\"/Login\"><input type=text name=user></form>",
        "<form action=\"/nifi-api/access/token\"><input type=text name=user></form>",
        "<input type=password name=secret>"})
    void additionalDetailsThatLookLikeASignInPageAreRejected(final String signInMarkup) throws Exception {
        server.enqueue(html(fixture()));
        server.enqueue(html("<html><body><h2>Sign in</h2>" + signInMarkup + "</body></html>"));
        assertThatThrownBy(() -> collector.collect(reference)).isInstanceOf(CollectionException.class)
                .hasMessageContaining("authentication page");
    }

    private static String fixture() throws Exception {
        try (var input = ComponentDocumentationCollectorTest.class.getResourceAsStream("/nifi1x/component.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MockResponse html(final String content) {
        return new MockResponse().setHeader("Content-Type", "text/html").setBody(content);
    }
}
