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

package org.qubership.nifi.tools.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.tools.kb.cli.BuildCommand;
import org.qubership.nifi.tools.kb.cli.BuilderConfig;
import org.qubership.nifi.tools.kb.cli.ExitCodes;
import org.qubership.nifi.tools.kb.cli.FailureClassifier;
import org.qubership.nifi.tools.kb.collect.ComponentCollector;
import org.qubership.nifi.tools.kb.docs.DeveloperGuideExtractor;
import org.qubership.nifi.tools.kb.docs.GuideCollector;
import org.qubership.nifi.tools.kb.docs.HtmlToMarkdown;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseValidator;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseWriter;
import org.qubership.nifi.tools.kb.output.OutputReplacer;
import org.qubership.nifi.tools.kb.render.JsonOutput;
import org.qubership.nifi.tools.nifi.common.api.NiFiCleanupException;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpResponse;
import picocli.CommandLine;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class KnowledgeBaseBuilderPreflightTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    private Path temp;
    private NiFiRestClient rest;
    private KnowledgeBaseBuilder builder;

    @BeforeEach
    void setup() throws Exception {
        rest = mock(NiFiRestClient.class);
        when(rest.httpClient()).thenReturn(mock(NiFiHttpClient.class));
        var transport = mock(TransportFactory.class);
        when(transport.create(any())).thenReturn(rest);
        when(rest.getJson(any()))
                .thenReturn(JSON.readTree("{\"processorTypes\":[],\"controllerServiceTypes\":[],\"rep"
                + "ortingTaskTypes\":[]}"));
        builder = new KnowledgeBaseBuilder(new BuilderVersion("test", "1"), transport, new ComponentCollector(),
                new GuideCollector(new HtmlToMarkdown(), new DeveloperGuideExtractor()),
                new KnowledgeBaseWriter(new JsonOutput(JSON)), new KnowledgeBaseValidator(), new OutputReplacer());
    }

    @Test
    void versionBoundariesSelectOnlyTheSupportedBackend() throws Exception {
        for (String version : List.of("1.25.9", "1.26.0", "1.28.1", "2.0.0", "2.4.9", "2.5.0", "3.0.0")) {
            version(version);
            var config = config("token", true);
            if (List.of("1.26.0", "1.28.1", "2.5.0").contains(version)) {
                builder.run(config);
                var manifest = JSON.readTree(config.outputDir().resolve("manifest.json").toFile());
                assertThat(manifest.path("nifi").path("minimumSupportedVersion").asText())
                        .isEqualTo(version.startsWith("1.") ? "1.26.0" : "2.5.0");
                assertThat(manifest.path("guides").path("developerGuide").path("sourcePath").asText())
                        .startsWith(version.startsWith("1.") ? "/nifi-docs/" : "/nifi-api/");
            } else {
                assertThatThrownBy(() -> builder.run(config)).hasMessageContaining("[1.26.0, 2.0.0)")
                        .hasMessageContaining("[2.5.0, 3.0.0)")
                        .satisfies(failure -> assertThat(FailureClassifier.classify(failure))
                                .isEqualTo(ExitCodes.UNSUPPORTED_TARGET));
            }
        }
        verify(rest, never()).getJson(URI.create("https://nifi/nifi-api/flow/cluster/summary"));
        noMutations();
    }

    @Test
    void optInAndCookieFailuresNeverMutateAndPreserveOutput() throws Exception {
        version("1.28.1");
        var output = temp.resolve("kb");
        Files.createDirectories(output);
        Files.writeString(output.resolve("sentinel"), "previous KB");
        assertThatThrownBy(() -> builder.run(config("token", false)))
                .hasMessageContaining("--allow-temporary-components");
        assertThatThrownBy(() -> builder.run(config("cookie", true))).hasMessageContaining("certificate or token");
        assertThat(Files.readString(output.resolve("sentinel"))).isEqualTo("previous KB");
        noMutations();
    }

    @Test
    void documentationProbeRunsEvenWhenGuidesAreSkipped() throws Exception {
        version("1.28.1");
        when(rest.getJson(URI.create("https://nifi/nifi-api/flow/processor-types")))
                .thenReturn(JSON.readTree("{\"processorTypes\":[{\"type\":\"org.example.Processor\",\""
                        + "bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1\"}}]}"));
        when(rest.httpClient().get(any(), eq("text/html"), any()))
                .thenReturn(new NiFiHttpResponse(404, "text/html", new byte[0]));
        assertThatThrownBy(() -> builder.run(config("token", true))).hasMessageContaining("/nifi-docs/components/");
        noMutations();
    }

    /**
     * A rejected create whose outcome cannot then be reconciled may have left the component behind,
     * so the run reports the cleanup failure, with its marker, rather than the 403 that preceded it.
     */
    @Test
    void cleanupFailureBehindARejectedCreateIsTheReportedFailure() throws Exception {
        var forbidden = rejectProcessorCreate();
        when(rest.getJson(URI.create("https://nifi/nifi-api/process-groups/group-id/processors")))
                .thenThrow(new NiFiApiException("GET", "https://nifi/nifi-api/process-groups/group-id/processors",
                        500, "", "request failed"));

        assertThatThrownBy(() -> builder.run(config("token", true)))
                .isInstanceOf(NiFiCleanupException.class)
                .hasMessageContaining("nifi-metadata-")
                .satisfies(failure -> assertThat(failure.getSuppressed()).contains(forbidden))
                .satisfies(failure -> assertThat(FailureClassifier.classify(failure))
                        .isEqualTo(ExitCodes.COLLECTION));
    }

    @Test
    void rejectedCreateThatLeftNothingBehindIsReportedAsItself() throws Exception {
        var forbidden = rejectProcessorCreate();
        when(rest.getJson(URI.create("https://nifi/nifi-api/process-groups/group-id/processors")))
                .thenReturn(JSON.readTree("{\"processors\":[]}"));

        assertThatThrownBy(() -> builder.run(config("token", true)))
                .isSameAs(forbidden)
                .satisfies(failure -> assertThat(FailureClassifier.classify(failure))
                        .isEqualTo(ExitCodes.AUTHORIZATION));
    }

    /**
     * Stubs a 1.x target with one processor type whose temporary group is created and whose processor
     * create is rejected with 403.
     *
     * @return the 403 failure the processor create throws
     */
    private NiFiApiException rejectProcessorCreate() throws Exception {
        version("1.28.1");
        when(rest.getJson(URI.create("https://nifi/nifi-api/flow/processor-types")))
                .thenReturn(JSON.readTree("{\"processorTypes\":[{\"type\":\"org.example.Processor\",\""
                        + "bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1\"}}]}"));
        when(rest.httpClient().get(any(), eq("text/html"), any())).thenReturn(new NiFiHttpResponse(200,
                "text/html", "<html><body><h1>Processor</h1><h3>Properties:</h3></body></html>"
                        .getBytes(StandardCharsets.UTF_8)));
        when(rest.getJson(URI.create("https://nifi/nifi-api/process-groups/root")))
                .thenReturn(JSON.readTree("{\"component\":{\"id\":\"root-id\"}}"));
        when(rest.postJson(eq(URI.create("https://nifi/nifi-api/process-groups/root-id/process-groups")),
                anyString())).thenReturn(JSON.readTree("{\"component\":{\"id\":\"group-id\"},"
                        + "\"revision\":{\"version\":0}}"));
        var forbidden = new NiFiApiException("POST", "https://nifi/nifi-api/process-groups/group-id/processors",
                403, "", "request failed");
        when(rest.postJson(eq(URI.create("https://nifi/nifi-api/process-groups/group-id/processors")),
                anyString())).thenThrow(forbidden);
        return forbidden;
    }

    @Test
    void nativeBackendStaysGetOnlyWithEitherOptInValue() throws Exception {
        version("2.10.0");
        builder.run(config("cookie", false));
        builder.run(config("token", true));
        verify(rest, never()).getJson(URI.create("https://nifi/nifi-api/flow/cluster/summary"));
        noMutations();
    }

    private void version(final String version) throws Exception {
        when(rest.getJson(URI.create("https://nifi/nifi-api/flow/about")))
                .thenReturn(JSON.readTree("{\"about\":{\"version\":\"" + version + "\"}}"));
    }

    private BuilderConfig config(final String auth, final boolean allowTemporaryComponents) {
        var args = new ArrayList<>(List.of("--nifi-url", "https://nifi", "--auth", auth,
                "--output-dir", temp.resolve("kb").toString(), "--skip-guides"));
        if (allowTemporaryComponents) {
            args.add("--allow-temporary-components");
        }
        var command = new BuildCommand(null, null);
        new CommandLine(command).parseArgs(args.toArray(String[]::new));
        return BuilderConfig.resolve(command, name -> "test-secret");
    }

    private void noMutations() {
        verify(rest, never()).postJson(any(), anyString());
        verify(rest, never()).delete(any());
    }
}
