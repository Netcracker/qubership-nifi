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
 * Sets up NiFi access policies for the admin user on the root process group.
 *
 * <p>Policies created (accepts HTTP 201 or 409):
 * <ul>
 *   <li>read  /process-groups/{root}</li>
 *   <li>write /process-groups/{root}</li>
 *   <li>write /operation/process-groups/{root}</li>
 *   <li>write /data/process-groups/{root}</li>
 *   <li>read  /provenance-data/process-groups/{root}</li>
 *   <li>read  /data/process-groups/{root}</li>
 * </ul>
 */
public class NifiAccessPolicies {

    private static final Logger LOG = LoggerFactory.getLogger(NifiAccessPolicies.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_CONFLICT = 409;

    private final String nifiUrl;
    private final HttpClient client;

    public NifiAccessPolicies(final String url, final HttpClient httpClient) {
        this.nifiUrl = url;
        this.client = httpClient;
    }

    /**
     * Fetches the root process group id and admin user, then creates all required policies.
     * Logs a warning and returns without failing if the NiFi instance does not support the policy API.
     */
    public void setup() throws Exception {
        String rootPgId = fetchRootProcessGroupId();
        if (rootPgId == null) {
            LOG.warn("Could not determine root process group id; skipping access policy setup");
            return;
        }

        String[] userInfo = fetchAdminUserInfo();
        if (userInfo == null) {
            LOG.warn("Could not find admin user; skipping access policy setup");
            return;
        }
        String userId = userInfo[0];
        String userIdentity = userInfo[1];

        createPolicy("read",  "/process-groups/" + rootPgId,                 userId, userIdentity);
        createPolicy("write", "/process-groups/" + rootPgId,                 userId, userIdentity);
        createPolicy("write", "/operation/process-groups/" + rootPgId,       userId, userIdentity);
        createPolicy("write", "/data/process-groups/" + rootPgId,            userId, userIdentity);
        createPolicy("read",  "/provenance-data/process-groups/" + rootPgId, userId, userIdentity);
        createPolicy("read",  "/data/process-groups/" + rootPgId,            userId, userIdentity);
    }

    private String fetchRootProcessGroupId() throws Exception {
        HttpResponse<String> resp = get("/nifi-api/flow/process-groups/root");
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("GET /nifi-api/flow/process-groups/root returned {}; body: {}",
                resp.statusCode(), resp.body());
            return null;
        }
        return MAPPER.readTree(resp.body()).path("processGroupFlow").path("id").asText(null);
    }

    /**
     * Returns {@code [userId, userIdentity]} for the admin user, or {@code null} if not found.
     *
     * @return two-element array {@code [userId, userIdentity]}, or {@code null} if the admin user was not found
     */
    private String[] fetchAdminUserInfo() throws Exception {
        HttpResponse<String> resp = get("/nifi-api/tenants/users");
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("GET /nifi-api/tenants/users returned {}; body: {}", resp.statusCode(), resp.body());
            return null;
        }
        for (JsonNode user : MAPPER.readTree(resp.body()).path("users")) {
            JsonNode component = user.path("component");
            String identity = component.path("identity").asText("");
            if ("CN=admin, OU=NIFI".equals(identity) || "admin".equals(identity)) {
                return new String[]{component.path("id").asText(), identity};
            }
        }
        return null;
    }

    private void createPolicy(final String action, final String resource,
                              final String userId, final String userIdentity) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("clientId", UUID.randomUUID().toString());
        revision.put("version", 0);
        body.set("revision", revision);
        body.put("disconnectedNodeAcknowledged", false);

        ObjectNode userRevision = MAPPER.createObjectNode();
        userRevision.put("version", 0);
        ObjectNode permissions = MAPPER.createObjectNode();
        permissions.put("canRead", true);
        permissions.put("canWrite", true);
        ObjectNode userComponent = MAPPER.createObjectNode();
        userComponent.put("id", userId);
        userComponent.put("identity", userIdentity);
        userComponent.put("configurable", true);
        ObjectNode userNode = MAPPER.createObjectNode();
        userNode.set("revision", userRevision);
        userNode.put("id", userId);
        userNode.set("permissions", permissions);
        userNode.set("component", userComponent);

        ArrayNode usersArray = MAPPER.createArrayNode();
        usersArray.add(userNode);

        ObjectNode component = MAPPER.createObjectNode();
        component.put("action", action);
        component.put("resource", resource);
        component.set("users", usersArray);
        component.set("userGroups", MAPPER.createArrayNode());
        body.set("component", component);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/policies"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status == HTTP_CREATED || status == HTTP_CONFLICT) {
            LOG.info("Policy [{} {}]: status {}", action, resource, status);
        } else {
            LOG.warn("Policy [{} {}]: unexpected status {}; body: {}", action, resource, status, resp.body());
        }
    }

    private HttpResponse<String> get(final String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + path))
            .header("Accept", "application/json")
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
