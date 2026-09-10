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

package org.qubership.nifi.tools.nifi.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiFiTemporaryComponentSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockWebServer server;
    private NiFiRestClient rest;
    private NiFiUriResolver resolver;
    private final Map<String, ObjectNode> resources = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private boolean missingDescriptors;
    private boolean wrongIdentity;
    private boolean lostResponse;
    private boolean missingId;
    private boolean missingRevision;
    private String staleRevisionMessage;
    private boolean vanished;
    private boolean ownershipChanged;
    private int deleteStatus;
    private int createStatus;
    private int groupStatus;
    private int sequence;

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(final RecordedRequest request) {
                try {
                    return respond(request);
                } catch (Exception failure) {
                    return new MockResponse().setResponseCode(500).setBody(failure.toString());
                }
            }
        });
        server.start();
        resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        rest = new NiFiRestClient(new NiFiHttpClient(NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(2)),
                resolver, NoAuthentication.INSTANCE, new NiFiHttpClient.Config(Duration.ofSeconds(2),
                1024 * 1024, 0, Duration.ofMillis(1), Duration.ofMillis(1), 2)), JSON);
    }

    @AfterEach
    void teardown() throws Exception {
        rest.close();
        server.close();
    }

    @Test
    void preservesRawDescriptorsWithoutValidationOrRelationshipSettingsForEveryKind() {
        try (var session = session()) {
            for (var kind : NiFiComponentKind.values()) {
                var result = session.collect(reference(kind, "a"));
                assertThat(result.path("propertyDescriptors").path("choice").path("defaultValue").asText())
                        .isEqualTo("raw-chain");
                assertThat(result.path("propertyDescriptors").path("choice").path("allowableValues").size())
                        .isEqualTo(1);
                assertThat(result.path("propertyDescriptors").path("service").path("allowableValues").toString())
                        .contains("private-service-id");
                assertThat(result.path("propertyDescriptors").path("service")
                        .path("identifiesControllerServiceBundle").path("artifact").asText()).isEqualTo("api");
                assertThat(result.toString()).doesNotContain("validationErrors", "autoTerminate", "retry",
                        "parentGroupId");
            }
        }
        assertThat(resources).isEmpty();
        assertThat(requests).contains("POST /nifi-api/controller/reporting-tasks");
    }

    @Test
    void keepsDuplicateTypeNamesInDifferentBundlesAndSupportsControllerScope() {
        try (var session = session()) {
            assertThat(session.collect(reference(NiFiComponentKind.PROCESSOR, "a")).path("bundle")
                    .path("artifact").asText()).isEqualTo("a");
            assertThat(session.collect(reference(NiFiComponentKind.PROCESSOR, "b")).path("bundle")
                    .path("artifact").asText()).isEqualTo("b");
            session.collect(reference(NiFiComponentKind.CONTROLLER_SERVICE, "c"), true);
        }
        assertThat(resources).isEmpty();
        assertThat(requests).contains("POST /nifi-api/controller/controller-services");
    }

    @Test
    void cleansAfterMissingDescriptorsAndIdentityMismatch() {
        for (int scenario = 0; scenario < 2; scenario++) {
            missingDescriptors = scenario == 0;
            wrongIdentity = scenario == 1;
            assertThatThrownBy(() -> {
                try (var session = session()) {
                    session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(resources).isEmpty();
        }
    }

    @Test
    void reconcilesMalformedCreateResponseWithoutRepeatingPost() {
        lostResponse = true;
        assertThatThrownBy(() -> {
            try (var session = session()) {
                session.collect(reference(NiFiComponentKind.REPORTING_TASK, "a"));
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(resources).isEmpty();
        assertThat(requests.stream().filter(value -> value.equals("POST /nifi-api/controller/reporting-tasks"))
                .count()).isEqualTo(1);
    }

    @Test
    void reconcilesMissingIdAndRevision() {
        for (int scenario = 0; scenario < 2; scenario++) {
            missingId = scenario == 0;
            missingRevision = scenario == 1;
            assertThatThrownBy(() -> {
                try (var session = session()) {
                    session.collect(reference(NiFiComponentKind.CONTROLLER_SERVICE, "a"));
                }
            }).isInstanceOf(RuntimeException.class);
            assertThat(resources).isEmpty();
        }
    }

    /**
     * NiFi's InvalidRevisionExceptionMapper sends 400 with the exception message, and NiFi words that
     * message two ways.
     *
     * @param message the 400 response body
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "[0, null, id-1] is not the most up-to-date revision. This component appears to have been modified",
        "Invalid Revision was given for component with ID 'id-1'"})
    void recoversOneStaleRevisionOnlyAfterCheckingOwnership(final String message) {
        staleRevisionMessage = message;
        try (var session = session()) {
            session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
        }
        assertThat(resources).isEmpty();
        assertThat(requests).anyMatch(value -> value.endsWith("?version=1"));
    }

    @Test
    void componentGoneBeforeItsDeleteCountsAsRemoved() {
        vanished = true;
        try (var session = session()) {
            session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
        }
        assertThat(resources).isEmpty();
        assertThat(requests).filteredOn(value -> value.startsWith("DELETE /nifi-api/processors/")).hasSize(1);
    }

    @Test
    void refusesStaleRevisionRecoveryWhenOwnershipChanged() {
        staleRevisionMessage = "[0, null, id-1] is not the most up-to-date revision.";
        ownershipChanged = true;
        assertThatThrownBy(() -> {
            try (var session = session()) {
                session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
            }
        }).isInstanceOf(NiFiCleanupException.class);
        assertThat(resources).hasSize(2);
    }

    /**
     * A 409 reports a component state that a current revision cannot change, and a 400 that names no
     * revision is not a stale revision, so neither earns the refresh and the second DELETE.
     *
     * @param status the DELETE response status
     */
    @ParameterizedTest
    @ValueSource(ints = {409, 400})
    void deleteFailureWithoutAStaleRevisionIsNotRetried(final int status) {
        deleteStatus = status;
        assertThatThrownBy(() -> {
            try (var session = session()) {
                session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
            }
        }).isInstanceOf(NiFiCleanupException.class);
        assertThat(requests).filteredOn(value -> value.startsWith("DELETE /nifi-api/processors/")).hasSize(1);
        assertThat(requests).noneMatch(value -> value.startsWith("GET /nifi-api/processors/"));
    }

    @Test
    void sessionWithFailedCleanupRefusesTheNextComponentWithoutARequest() {
        missingDescriptors = true;
        deleteStatus = 403;
        var session = session();
        assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.PROCESSOR, "a")))
                .isInstanceOf(IllegalStateException.class);
        int sent = requests.size();
        assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.PROCESSOR, "b")))
                .isInstanceOf(NiFiCleanupException.class);
        assertThat(requests).hasSize(sent);
        assertThatThrownBy(session::close).isInstanceOf(NiFiCleanupException.class);
    }

    @Test
    void controllerScopedReportingTaskReconcilesAtTheReportingTaskListing() {
        createStatus = 403;
        try (var session = session()) {
            assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.REPORTING_TASK, "a"), true))
                    .isInstanceOf(NiFiApiException.class);
        }
        assertThat(requests).contains("GET /nifi-api/flow/reporting-tasks");
    }

    @Test
    void controllerScopedServiceReconcilesAtTheControllerServiceListing() {
        createStatus = 403;
        try (var session = session()) {
            assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.CONTROLLER_SERVICE, "a"), true))
                    .isInstanceOf(NiFiApiException.class);
        }
        assertThat(requests).contains("GET /nifi-api/flow/controller/controller-services");
    }

    @Test
    void rejectedGroupCreationIsNotRepeatedForTheNextComponent() {
        groupStatus = 403;
        try (var session = session()) {
            assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.PROCESSOR, "a")))
                    .isInstanceOf(NiFiApiException.class);
            int sent = requests.size();
            assertThatThrownBy(() -> session.collect(reference(NiFiComponentKind.PROCESSOR, "b")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(requests).hasSize(sent);
        }
    }

    @Test
    void cleanupFailureIsFatalAndRetainsPrimaryExtractionFailure() {
        missingDescriptors = true;
        deleteStatus = 403;
        assertThatThrownBy(() -> {
            try (var session = session()) {
                session.collect(reference(NiFiComponentKind.PROCESSOR, "a"));
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing descriptors")
                .satisfies(failure -> assertThat(failure.getSuppressed()).isNotEmpty());
        assertThat(resources).hasSize(2);
        assertThat(requests.stream().filter(value -> value.startsWith("DELETE ")).count()).isEqualTo(1);
    }

    @Test
    void unusedSessionDoesNotMakeRequests() {
        try (var session = session()) {
            assertThat(requests).isEmpty();
        }
        assertThat(requests).isEmpty();
    }

    private NiFiTemporaryComponentSession session() {
        return new NiFiTemporaryComponentSession(rest, resolver, null);
    }

    private static NiFiComponentReference reference(final NiFiComponentKind kind, final String artifact) {
        return new NiFiComponentReference(kind, "g", artifact, "1.28.1", "org.example.Component");
    }

    private MockResponse respond(final RecordedRequest request) throws Exception {
        String path = request.getPath();
        requests.add(request.getMethod() + " " + path);
        if (path.equals("/nifi-api/process-groups/root")) {
            return json(JSON.readTree("{\"component\":{\"id\":\"root-id\"}}"));
        }
        if (request.getMethod().equals("POST")) {
            ObjectNode entity = (ObjectNode) JSON.readTree(request.getBody().readUtf8());
            ObjectNode component = (ObjectNode) entity.path("component");
            boolean group = !component.has("type");
            int rejection = group ? groupStatus : createStatus;
            if (rejection != 0) {
                return new MockResponse().setResponseCode(rejection);
            }
            String id = "id-" + sequence++;
            component.put("id", id);
            String plural = group ? "process-groups" : path.substring(path.lastIndexOf('/') + 1);
            if (!group) {
                ObjectNode holder = plural.equals("processors") ? component.putObject("config") : component;
                if (!missingDescriptors) {
                    holder.set("descriptors", JSON.readTree("""
                            {"choice":{"defaultValue":"raw-chain",
                            "allowableValues":[{"allowableValue":{"value":"raw-chain",
                            "displayName":"Chain"}}],
                            "expressionLanguageScope":"Variable Registry Only",
                            "dependencies":[{"propertyName":"other",
                            "dependentValues":["raw-custom"]}]},
                            "service":{"identifiesControllerService":"org.example.Api",
                            "identifiesControllerServiceBundle":{"group":"g",
                            "artifact":"api",
                            "version":"1"},
                            "allowableValues":[{"allowableValue":{"value":"private-service-id"}}]}}
                            """));
                }
                component.putArray("relationships").addObject().put("name", "success").put("description", "ok")
                        .put("autoTerminate", false).put("retry", false);
                component.putArray("validationErrors").add("unconfigured");
                if (wrongIdentity) {
                    component.put("type", "org.example.Wrong");
                }
            }
            resources.put("/nifi-api/" + plural + "/" + id, entity.deepCopy());
            if (!group && lostResponse) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody("truncated");
            }
            if (!group && missingId) {
                component.remove("id");
            }
            if (!group && missingRevision) {
                entity.remove("revision");
            }
            return json(entity);
        }
        if (request.getMethod().equals("DELETE")) {
            String endpoint = path.split("\\?")[0];
            if (deleteStatus != 0) {
                return new MockResponse().setResponseCode(deleteStatus);
            }
            if (staleRevisionMessage != null && endpoint.contains("/processors/")) {
                String message = staleRevisionMessage;
                staleRevisionMessage = null;
                resources.get(endpoint).putObject("revision").put("version", 1);
                if (ownershipChanged) {
                    ((ObjectNode) resources.get(endpoint).path("component")).put("name", "someone-else");
                    deleteStatus = 409;
                }
                return new MockResponse().setResponseCode(400).setHeader("Content-Type", "text/plain")
                        .setBody(message);
            }
            if (vanished && endpoint.contains("/processors/")) {
                resources.remove(endpoint);
                return new MockResponse().setResponseCode(404);
            }
            resources.remove(endpoint);
            return json(JSON.createObjectNode());
        }
        if (resources.containsKey(path)) {
            return json(resources.get(path));
        }
        if (path.matches("/nifi-api/(processors|controller-services|reporting-tasks)/[^/]+")) {
            return new MockResponse().setResponseCode(404);
        }
        ObjectNode list = JSON.createObjectNode();
        String key = path.endsWith("reporting-tasks") ? "reportingTasks"
                : path.endsWith("controller-services") ? "controllerServices"
                : path.endsWith("processors") ? "processors" : "processGroups";
        var array = list.putArray(key);
        resources.forEach((endpoint, entity) -> {
            if (endpoint.contains("/" + path.substring(path.lastIndexOf('/') + 1) + "/")) {
                array.add(entity);
            }
        });
        return json(list);
    }

    private static MockResponse json(final Object value) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString());
    }
}
