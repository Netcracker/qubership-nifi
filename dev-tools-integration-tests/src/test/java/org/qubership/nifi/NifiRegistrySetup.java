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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Handles NiFi Registry client setup in NiFi and bucket/flow/version management
 * in NiFi Registry for integration tests.
 */
public final class NifiRegistrySetup {

    private static final Logger LOG = LoggerFactory.getLogger(NifiRegistrySetup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_CONFLICT = 409;
    private static final String REGISTRY_CLIENT_TYPE =
        "org.apache.nifi.registry.flow.NifiRegistryFlowRegistryClient";

    private NifiRegistrySetup() {
    }

    // -------------------------------------------------------------------------
    // NiFi — registry client lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates a NiFi Registry client in NiFi pointing to {@code registryIntUrl}.
     *
     * @param nifiUrl NiFi base URL
     * @param registryIntUrl NiFi Registry internal URL
     * @param httpClient HTTP client to use for requests
     * @return the created registry client id
     */
    public static String setupRegistryClient(final String nifiUrl,
                                             final String registryIntUrl,
                                             final HttpClient httpClient) throws Exception {
        String version = resolveRegistryClientVersion(nifiUrl, httpClient);
        LOG.info("Resolved NiFi Registry client bundle version: {}", version);

        String clientId = UUID.randomUUID().toString();

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("clientId", clientId);
        revision.put("version", 0);

        ObjectNode registryComponent = MAPPER.createObjectNode();
        registryComponent.put("name", "IT-Registry");
        registryComponent.put("description", "Registry client for integration tests");
        registryComponent.put("type", REGISTRY_CLIENT_TYPE);

        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("group", "org.apache.nifi");
        bundle.put("artifact", "nifi-flow-registry-client-nar");
        bundle.put("version", version);
        registryComponent.set("bundle", bundle);

        ObjectNode properties = MAPPER.createObjectNode();
        properties.put("url", registryIntUrl);
        properties.putNull("ssl-context-service");
        registryComponent.set("properties", properties);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("revision", revision);
        body.set("registry", registryComponent);
        body.set("component", registryComponent.deepCopy());

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/controller/registry-clients"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_CREATED) {
            throw new IllegalStateException(
                "Failed to create registry client: HTTP " + resp.statusCode() + " — " + resp.body());
        }

        String registryClientId = MAPPER.readTree(resp.body()).path("id").asText();
        LOG.info("Created NiFi Registry client id={}", registryClientId);
        return registryClientId;
    }

    /**
     * Deletes the NiFi Registry client with the given id from NiFi.
     *
     * @param nifiUrl NiFi base URL
     * @param registryClientId id of the registry client to delete
     * @param httpClient HTTP client to use for requests
     */
    public static void deleteRegistryClient(final String nifiUrl,
                                            final String registryClientId,
                                            final HttpClient httpClient) throws Exception {
        HttpRequest getReq = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/controller/registry-clients/" + registryClientId))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        if (getResp.statusCode() != HTTP_OK) {
            LOG.warn("GET registry client {} returned {}; skipping delete", registryClientId, getResp.statusCode());
            return;
        }
        String version = MAPPER.readTree(getResp.body()).path("revision").path("version").asText("0");

        HttpRequest delReq = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/controller/registry-clients/"
                + registryClientId + "?version=" + version))
            .header("Accept", "application/json")
            .DELETE()
            .build();
        HttpResponse<String> delResp = httpClient.send(delReq, HttpResponse.BodyHandlers.ofString());
        if (delResp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE registry client {} returned {}; body: {}",
                registryClientId, delResp.statusCode(), delResp.body());
        } else {
            LOG.info("Deleted NiFi Registry client id={}", registryClientId);
        }
    }

    // -------------------------------------------------------------------------
    // NiFi Registry — NiFi user / access policy
    // -------------------------------------------------------------------------

    private static String lookupUser(final String registryUrl,
                                     final HttpClient httpClient,
                                     final String identity) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/nifi-registry-api/tenants/users"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                    "GET /nifi-registry-api/tenants/users returned HTTP " + resp.statusCode());
        }

        ArrayNode allUsers = (ArrayNode) MAPPER.readTree(resp.body());
        String identifier = null;
        for (JsonNode user : allUsers) {
            if (identity.equals(user.path("identity").asText())) {
                identifier = user.path("identifier").asText();
                break;
            }
        }
        return identifier;
    }

    /**
     * Creates NiFi user and sets up necessary access policies in NiFi Registry.
     *
     * @param registryUrl NiFi Registry base URL
     * @param httpClient HTTP client to use for requests
     * @param identity user identity to create or update
     */
    public static void createOrUpdateUser(final String registryUrl,
                                      final HttpClient httpClient,
                                      final String identity) throws Exception {
        String userId = lookupUser(registryUrl, httpClient, identity);
        if (userId == null) {
            //create
            ObjectNode body = MAPPER.createObjectNode();
            body.put("identity", identity);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(registryUrl + "/nifi-registry-api/tenants/users"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != HTTP_CREATED && resp.statusCode() != HTTP_CONFLICT) {
                throw new IllegalStateException(
                        "Failed to create NiFi user: HTTP " + resp.statusCode() + " — " + resp.body());
            }
            userId = MAPPER.readTree(resp.body()).path("identifier").asText();
            LOG.info("Created NiFi user identity={} id={}", identity, userId);
        } else {
            LOG.info("Found existing user identity={} id={}", identity, userId);
        }
        addUserToPolicy(registryUrl, httpClient, userId, "read/proxy");
    }

    private static ObjectNode getRegistryPolicy(final String registryUrl,
                                            final HttpClient httpClient,
                                            final String policyPath) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/nifi-registry-api/policies/" + policyPath))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                    "GET /nifi-registry-api/policies/" + policyPath + " returned HTTP " + resp.statusCode());
        }

        JsonNode jsonNode = MAPPER.readTree(resp.body());
        if (!jsonNode.isObject()) {
            throw new IllegalStateException(
                    "GET /nifi-registry-api/policies/" + policyPath + " returned not an object: "
                            + MAPPER.writeValueAsString(jsonNode));
        }
        return (ObjectNode) jsonNode;
    }

    private static void addUserToPolicy(final String registryUrl,
                                        final HttpClient httpClient,
                                        final String userIdentifier, final String policyPath) throws Exception {
        LOG.info("Adding user with id = {} to policy with path = {}", userIdentifier, policyPath);
        ObjectNode policyNode = getRegistryPolicy(registryUrl, httpClient, policyPath);
        ArrayNode usersNode = (ArrayNode) policyNode.path("users");
        ObjectNode user = MAPPER.createObjectNode();
        user.put("identifier", userIdentifier);
        usersNode.add(user);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/nifi-registry-api/policies/"
                        + policyNode.path("identifier").asText()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(policyNode)))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                    "Failed to update policy " + policyPath + ": HTTP " + resp.statusCode() + " — " + resp.body());
        }
    }

    // -------------------------------------------------------------------------
    // NiFi Registry — bucket / flow / version lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates a bucket in NiFi Registry.
     *
     * @param registryUrl NiFi Registry base URL
     * @param httpClient HTTP client to use for requests
     * @param name bucket name
     * @return the bucket identifier
     */
    public static String createBucket(final String registryUrl,
                                      final HttpClient httpClient,
                                      final String name) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name);
        body.put("description", "IT test bucket");

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(registryUrl + "/nifi-registry-api/buckets"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK && resp.statusCode() != HTTP_CREATED) {
            throw new IllegalStateException(
                "Failed to create bucket: HTTP " + resp.statusCode() + " — " + resp.body());
        }

        String bucketId = MAPPER.readTree(resp.body()).path("identifier").asText();
        LOG.info("Created registry bucket name={} id={}", name, bucketId);
        return bucketId;
    }

    /**
     * Creates a flow in the given bucket in NiFi Registry.
     *
     * @param registryUrl NiFi Registry base URL
     * @param httpClient HTTP client to use for requests
     * @param bucketId id of the bucket to create the flow in
     * @param name flow name
     * @return the flow identifier
     */
    public static String createFlow(final String registryUrl,
                                    final HttpClient httpClient,
                                    final String bucketId,
                                    final String name) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name);
        body.put("description", "IT test flow");
        body.put("bucketIdentifier", bucketId);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(registryUrl + "/nifi-registry-api/buckets/" + bucketId + "/flows"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK && resp.statusCode() != HTTP_CREATED) {
            throw new IllegalStateException(
                "Failed to create flow: HTTP " + resp.statusCode() + " — " + resp.body());
        }

        String flowId = MAPPER.readTree(resp.body()).path("identifier").asText();
        LOG.info("Created registry flow name={} id={}", name, flowId);
        return flowId;
    }

    /**
     * Creates the first version of a flow in NiFi Registry.
     *
     * @param registryUrl NiFi Registry base URL
     * @param httpClient HTTP client to use for requests
     * @param bucketId id of the bucket containing the flow
     * @param flowId id of the flow to version
     * @param flowContents flow contents JSON node
     * @return the version number (normally {@code 1})
     */
    public static int createFlowVersion(final String registryUrl,
                                        final HttpClient httpClient,
                                        final String bucketId,
                                        final String flowId,
                                        final JsonNode flowContents) throws Exception {
        ObjectNode snapshotMetadata = MAPPER.createObjectNode();
        snapshotMetadata.put("bucketIdentifier", bucketId);
        snapshotMetadata.put("flowIdentifier", flowId);
        snapshotMetadata.put("comments", "IT initial version");
        // -1 tells the Registry to auto-assign the next sequential version number
        snapshotMetadata.put("version", -1);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("snapshotMetadata", snapshotMetadata);
        body.set("flowContents", flowContents);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(registryUrl + "/nifi-registry-api/buckets/"
                + bucketId + "/flows/" + flowId + "/versions"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK && resp.statusCode() != HTTP_CREATED) {
            throw new IllegalStateException(
                "Failed to create flow version: HTTP " + resp.statusCode() + " — " + resp.body());
        }

        int version = MAPPER.readTree(resp.body()).path("snapshotMetadata").path("version").asInt(1);
        LOG.info("Created flow version={} for flowId={}", version, flowId);
        return version;
    }

    /**
     * Deletes a bucket from NiFi Registry (cascades to all flows and versions).
     *
     * @param registryUrl NiFi Registry base URL
     * @param httpClient HTTP client to use for requests
     * @param bucketId id of the bucket to delete
     */
    public static void deleteBucket(final String registryUrl,
                                    final HttpClient httpClient,
                                    final String bucketId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(registryUrl + "/nifi-registry-api/buckets/" + bucketId))
            .header("Accept", "application/json")
            .DELETE()
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE bucket {} returned {}; body: {}", bucketId, resp.statusCode(), resp.body());
        } else {
            LOG.info("Deleted registry bucket id={}", bucketId);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String resolveRegistryClientVersion(final String nifiUrl,
                                                       final HttpClient httpClient) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/controller/registry-types"))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                "GET /nifi-api/controller/registry-types returned HTTP " + resp.statusCode());
        }

        for (JsonNode type : MAPPER.readTree(resp.body()).path("flowRegistryClientTypes")) {
            if (REGISTRY_CLIENT_TYPE.equals(type.path("type").asText())) {
                return type.path("bundle").path("version").asText();
            }
        }
        throw new IllegalStateException(
            "Registry client type not found: " + REGISTRY_CLIENT_TYPE);
    }
}
