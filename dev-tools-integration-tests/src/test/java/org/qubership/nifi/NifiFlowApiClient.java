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
package org.qubership.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Thin HTTP client for the NiFi REST API used by integration tests.
 *
 * <p>Each instance is bound to a single NiFi base URL and an mTLS-capable
 * {@link HttpClient} (see {@link NifiMtlsClient}).
 */
public class NifiFlowApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(NifiFlowApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;

    private final String nifiUrl;
    private final HttpClient httpClient;

    public NifiFlowApiClient(final String url, final HttpClient client) {
        this.nifiUrl = url;
        this.httpClient = client;
    }

    // -------------------------------------------------------------------------
    // Flow / version info
    // -------------------------------------------------------------------------

    public String fetchNifiVersion() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/flow/about"))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                    "GET /nifi-api/flow/about returned HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body()).path("about").path("version").asText();
    }

    public JsonNode fetchControllerServiceTypes() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/flow/controller-service-types"))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                    "GET /nifi-api/flow/controller-service-types returned HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body()).path("controllerServiceTypes");
    }

    // -------------------------------------------------------------------------
    // Controller services
    // -------------------------------------------------------------------------

    /**
     * Creates a controller service in the root process group.
     *
     * @param body JSON request body
     * @return full response JSON (includes {@code id}, {@code revision}, {@code status})
     */
    public JsonNode createControllerService(final String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/process-groups/root/controller-services"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Create controller service: status={}", resp.statusCode());
        assertEquals(HTTP_CREATED, resp.statusCode(),
                "Expected HTTP 201 when creating controller service. Response: " + resp.body());
        return MAPPER.readTree(resp.body());
    }

    public JsonNode getControllerServiceById(final String id) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/controller-services/" + id))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Get CS response for id={}: status={}", id, resp.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Get CS response body for id={}: body={}", id, resp.body());
        }
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when getting CS. Response: " + resp.body());
        return MAPPER.readTree(resp.body());
    }

    public void deleteControllerService(final String id, final String version) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/controller-services/" + id + "?version=" + version))
                .header("Accept", "application/json")
                .DELETE()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE controller service {} returned status {}; body: {}",
                    id, resp.statusCode(), resp.body());
        }
    }

    // -------------------------------------------------------------------------
    // Controller services state for a process group
    // -------------------------------------------------------------------------

    public void changeControllerServicesStateForPg(final String pgId, final String targetState)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", pgId);
        body.put("state", targetState);
        body.put("disconnectedNodeAcknowledged", Boolean.FALSE);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/flow/process-groups/" + pgId + "/controller-services"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Change CS to state {} response: status={}", targetState, resp.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Change CS to state {} response body: body={}", targetState, resp.body());
        }
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when changing controller services state to " + targetState
                        + ". Response: " + resp.body());
    }

    /**
     * Returns the {@code controllerServices} array for the given process group.
     * Calls {@code GET /nifi-api/flow/process-groups/{pgId}/controller-services}.
     *
     * @param pgId process group id
     * @return {@code controllerServices} JSON array node
     */
    public JsonNode getControllerServicesForPg(final String pgId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/flow/process-groups/" + pgId + "/controller-services"))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when getting controller services for PG. Response: " + resp.body());
        return MAPPER.readTree(resp.body()).path("controllerServices");
    }

    /**
     * Waits (up to 30 s) for all controller services in a process group to reach
     * {@code targetState}. An empty services list satisfies the condition immediately.
     *
     * @param pgId        process group id
     * @param targetState desired CS state (e.g. {@code "ENABLED"} or {@code "DISABLED"})
     */
    public void waitForControllerServicesState(final String pgId, final String targetState) {
        LOG.info("Waiting for all controller services in PG {} to reach state {}", pgId, targetState);
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> {
                    JsonNode csArray = getControllerServicesForPg(pgId);
                    if (!csArray.isArray() || csArray.isEmpty()) {
                        return true;
                    }
                    for (JsonNode cs : csArray) {
                        if (!targetState.equals(cs.path("component").path("state").asText())) {
                            return false;
                        }
                    }
                    return true;
                });
        LOG.info("All controller services in PG {} reached state {}", pgId, targetState);
    }

    // -------------------------------------------------------------------------
    // Process groups
    // -------------------------------------------------------------------------

    /**
     * Imports a versioned process group under the root process group.
     *
     * @param bucketId         registry bucket id
     * @param flowId           registry flow id
     * @param version          flow version number
     * @param registryClientId NiFi registry client id
     * @return full response JSON (includes {@code id})
     */
    public JsonNode importProcessGroup(final String bucketId, final String flowId,
                                       final int version, final String registryClientId) throws Exception {
        String body = MAPPER.writeValueAsString(buildVersionedImportBody(bucketId, flowId, version, registryClientId));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/process-groups/root/process-groups"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Import process group: status={}", resp.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Import process group response body: body={}", resp.body());
        }
        assertEquals(HTTP_CREATED, resp.statusCode(),
                "Expected HTTP 201 when creating process group. Response: " + resp.body());
        return MAPPER.readTree(resp.body());
    }

    public JsonNode getProcessGroupById(final String id) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/process-groups/" + id))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Get PG response: status={}", resp.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Get PG response body: body={}", resp.body());
        }
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when getting PG. Response: " + resp.body());
        return MAPPER.readTree(resp.body());
    }

    public JsonNode getProcessGroupFlowById(final String id) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/flow/process-groups/" + id))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("Get flow PG response: status={}", resp.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Get flow PG response body: body={}", resp.body());
        }
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when getting flow PG. Response: " + resp.body());
        return MAPPER.readTree(resp.body());
    }

    public void deleteProcessGroup(final String id, final String version) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/process-groups/" + id + "?version=" + version))
                .header("Accept", "application/json")
                .DELETE()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE process group {} returned status {}; body: {}",
                    id, resp.statusCode(), resp.body());
        }
    }

    /**
     * Waits (up to 30 s) for the process group's {@code invalidCount} to reach an acceptable
     * terminal state, then asserts that no invalid components remain.
     *
     * <p>For NiFi 2.5.0, {@code invalidCount == 1} caused by the known
     * {@code PutS3Object} sensitive-properties issue is silently accepted.
     *
     * @param pgId        process group id
     * @param nifiVersion NiFi version string (e.g. {@code "2.5.0"})
     */
    public void waitForPgValidation(final String pgId, final String nifiVersion)
            throws IOException, InterruptedException {
        LOG.info("Waiting for PG {} invalidCount to reach 0", pgId);
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .until(() -> {
                    int count = getProcessGroupById(pgId).path("invalidCount").asInt();
                    // For NiFi 2.5.0, invalidCount=1 (PutS3Object known issue) is also terminal
                    return count == 0 || ("2.5.0".equals(nifiVersion) && count == 1);
                });

        JsonNode pgJson = getProcessGroupById(pgId);
        int invalidCount = pgJson.path("invalidCount").asInt();
        if (invalidCount == 0) {
            LOG.info("PG {} has no invalid components", pgId);
            return;
        }

        // invalidCount > 0 — gather details and check for known exceptions
        StringBuilder validationErrorsMessage = new StringBuilder();
        JsonNode mainPgFlow = getProcessGroupFlowById(pgId);
        LOG.info("Processing processors validation errors for PG with id = {}", pgId);
        addValidationErrorsForPg(mainPgFlow, validationErrorsMessage);
        JsonNode childPgsNode = mainPgFlow.path("processGroupFlow").path("flow").path("processGroups");
        if (childPgsNode.isArray()) {
            for (JsonNode childPg : (ArrayNode) childPgsNode) {
                int childInvalidCount = childPg.path("invalidCount").asInt();
                String childPgId = childPg.path("id").asText();
                LOG.info("Child PG with id = {} has invalidCount = {}", childPgId, childInvalidCount);
                if (childInvalidCount > 0) {
                    LOG.info("Processing processors validation errors for child PG with id = {}", childPgId);
                    addValidationErrorsForPg(getProcessGroupFlowById(childPgId), validationErrorsMessage);
                }
            }
        }
        if ("2.5.0".equals(nifiVersion)
                && invalidCount == 1
                && !validationErrorsMessage.isEmpty()
                && validationErrorsMessage.toString().contains(
                        "Processor name = PutS3Object. Validation errors: "
                        + "['Component' is invalid because Sensitive Dynamic Properties [Access Key, "
                        + "proxy-user-password, Secret Key] configured but not supported,].")) {
            LOG.warn("Invalid PutS3Object processor in 2.5.0, skipping. Validation errors = {}",
                    validationErrorsMessage);
            return;
        }
        assertEquals(0, invalidCount, "Created PG must not have invalid components. "
                + "Validation errors: " + validationErrorsMessage);
    }

    private static void addValidationErrorsForPg(final JsonNode getResponseJson,
                                                  final StringBuilder validationErrorsMessage) {
        JsonNode processorsNode = getResponseJson.path("processGroupFlow").path("flow").path("processors");
        if (!processorsNode.isArray()) {
            return;
        }
        LOG.info("Processing processors validation errors under PG with id = {}",
                getResponseJson.path("processGroupFlow").path("id").asText());
        for (JsonNode processorNode : (ArrayNode) processorsNode) {
            JsonNode component = processorNode.path("component");
            if ("INVALID".equals(component.path("validationStatus").asText())) {
                validationErrorsMessage.append("Processor name = ")
                        .append(component.path("name").asText())
                        .append(". Validation errors: [");
                for (JsonNode validationError : (ArrayNode) component.path("validationErrors")) {
                    validationErrorsMessage.append(validationError.asText()).append(",");
                }
                validationErrorsMessage.append("].");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ObjectNode buildVersionedImportBody(final String bucketId, final String flowId,
                                                final int version, final String registryClientId) {
        ObjectNode importBody = MAPPER.createObjectNode();

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("version", 0);
        importBody.set("revision", revision);
        importBody.put("disconnectedNodeAcknowledged", false);

        ObjectNode position = MAPPER.createObjectNode();
        position.put("x", 0.0);
        position.put("y", 0.0);

        ObjectNode versionControlInfo = MAPPER.createObjectNode();
        versionControlInfo.put("registryId", registryClientId);
        versionControlInfo.put("bucketId", bucketId);
        versionControlInfo.put("flowId", flowId);
        versionControlInfo.put("version", version);

        ObjectNode component = MAPPER.createObjectNode();
        component.put("name", "IT-Test-Group");
        component.set("position", position);
        component.set("versionControlInformation", versionControlInfo);
        importBody.set("component", component);

        return importBody;
    }
}
