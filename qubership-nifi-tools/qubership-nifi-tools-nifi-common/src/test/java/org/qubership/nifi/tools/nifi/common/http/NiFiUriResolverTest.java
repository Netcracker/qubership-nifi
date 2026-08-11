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

package org.qubership.nifi.tools.nifi.common.http;

import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiFiUriResolverTest {

    @Test
    void normalizesOriginRoot() {
        assertThat(NiFiUriResolver.fromBaseUrl("https://nifi.example.com").baseUrl())
                .isEqualTo("https://nifi.example.com");
    }

    @Test
    void stripsTrailingNifiSuffix() {
        assertThat(NiFiUriResolver.fromBaseUrl("https://nifi.example.com/nifi").baseUrl())
                .isEqualTo("https://nifi.example.com");
    }

    @Test
    void stripsTrailingNifiSuffixBelowProxyPrefix() {
        assertThat(NiFiUriResolver.fromBaseUrl("https://gateway.example.com/dataflow/nifi").baseUrl())
                .isEqualTo("https://gateway.example.com/dataflow");
    }

    @Test
    void stripsTrailingNifiApiSuffix() {
        assertThat(NiFiUriResolver.fromBaseUrl("https://nifi.example.com/nifi-api").baseUrl())
                .isEqualTo("https://nifi.example.com");
    }

    @Test
    void rejectsNonHttpsWhenRequired() {
        assertThatThrownBy(() -> NiFiUriResolver.fromBaseUrl("http://nifi.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsQuery() {
        assertThatThrownBy(() -> NiFiUriResolver.fromBaseUrl("https://nifi.example.com/nifi?x=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsFragment() {
        assertThatThrownBy(() -> NiFiUriResolver.fromBaseUrl("https://nifi.example.com/nifi#frag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment");
    }

    @Test
    void resolvesApiPathAgainstProxyPrefix() {
        final NiFiUriResolver resolver =
                NiFiUriResolver.fromBaseUrl("https://gateway.example.com/dataflow/nifi");
        assertThat(resolver.resolve("/nifi-api/flow/about").toString())
                .isEqualTo("https://gateway.example.com/dataflow/nifi-api/flow/about");
    }

    @Test
    void encodesDefinitionSegments() {
        final NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl("https://nifi.example.com");
        final URI uri = resolver.resolveDefinition(NiFiComponentKind.PROCESSOR,
                "org.apache.nifi", "nifi-standard-nar", "2.5.0", "org.apache.nifi.Foo$Bar");
        assertThat(uri.toString()).isEqualTo(
                "https://nifi.example.com/nifi-api/flow/processor-definition/"
                        + "org.apache.nifi/nifi-standard-nar/2.5.0/org.apache.nifi.Foo%24Bar");
    }

    @Test
    void encodesSeparatorsAndSpacesWithinASingleSegment() {
        final NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl("https://nifi.example.com");
        final URI uri = resolver.resolveDefinition(NiFiComponentKind.CONTROLLER_SERVICE,
                "org.apache.nifi", "nifi-standard-nar", "2.5.0", "a/b c");
        assertThat(uri.getRawPath()).endsWith("/a%2Fb%20c");
        assertThat(uri.getPath()).endsWith("/a/b c");
    }

    @Test
    void sameOriginDistinguishesHostAndPort() {
        final NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl("https://nifi.example.com:9443/nifi");
        assertThat(resolver.isSameOrigin(URI.create("https://nifi.example.com:9443/nifi-api/flow/about"))).isTrue();
        assertThat(resolver.isSameOrigin(URI.create("https://nifi.example.com/nifi-api/flow/about"))).isFalse();
        assertThat(resolver.isSameOrigin(URI.create("https://evil.example.com:9443/x"))).isFalse();
    }
}
