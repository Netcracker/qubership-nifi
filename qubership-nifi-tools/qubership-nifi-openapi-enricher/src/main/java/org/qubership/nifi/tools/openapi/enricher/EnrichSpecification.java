/*
 * Copyright 2020-2026 NetCracker Technology Corporation
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
package org.qubership.nifi.tools.openapi.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Enriches Apache NiFi JSON OpenAPI specification with additional data to pass API Hub validations.
 */
public class EnrichSpecification {
    private static final Logger LOG = LoggerFactory.getLogger(EnrichSpecification.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Default constructor.
     */
    public EnrichSpecification() {
        //default constructor
    }

    /**
     * Enriches Apache NiFi JSON OpenAPI specification with additional data to pass API Hub validations.
     * @param spec JsonNode with Apache NiFi OpenAPI specification
     * @return modified specification
     */
    public JsonNode enrichNiFi(JsonNode spec) {
        addServerIfMissing(spec, "/nifi-api");
        addMissingOperationDescriptions(spec);
        addTagsDescriptions(spec);
        return spec;
    }

    private void addServerIfMissing(JsonNode spec, String url) {
        JsonNode serversNode = spec.path("servers");
        if (serversNode.isNull() || serversNode.isMissingNode()) {
            ArrayNode serversArray = MAPPER.createArrayNode();
            ObjectNode serverObject = MAPPER.createObjectNode();
            serverObject.put("url", url);
            serversArray.add(serverObject);
            ((ObjectNode) spec).set("servers", serversArray);
        }
    }

    private String getDefaultResponseDescription(String responseCode) {
        if ("200".equals(responseCode)) {
            return "successful operation";
        } else if ("201".equals(responseCode)) {
            return "successful operation";
        } else if ("202".equals(responseCode)) {
            return "successful operation";
        } else if ("default".equals(responseCode)) {
            return "successful operation";
        }
        return null;
    }

    private void addMissingResponseDescriptions(ObjectNode methodNode) {
        ObjectNode responsesNodeObj = (ObjectNode) methodNode.path("responses");

        for (Map.Entry<String, JsonNode> responseEntry : responsesNodeObj.properties()) {
            ObjectNode responseNodeObj = (ObjectNode) responseEntry.getValue();
            if (!responseNodeObj.has("description")) {
                String defaultDescription = getDefaultResponseDescription(responseEntry.getKey());
                if (defaultDescription != null) {
                    responseNodeObj.put("description", defaultDescription);
                }
            }
        }
    }

    private void addMissingOperationDescriptions(JsonNode spec) {
        ObjectNode pathsNode = (ObjectNode) spec.path("paths");
        for (JsonNode pathNode : pathsNode) {
            if (pathNode.isObject()) {
                ObjectNode pathNodeObj = (ObjectNode) pathNode;
                for (Map.Entry<String, JsonNode> methodEntry : pathNodeObj.properties()) {
                    if (methodEntry.getValue().isObject()) {
                        ObjectNode methodNodeObj = (ObjectNode) methodEntry.getValue();
                        addMissingResponseDescriptions(methodNodeObj);
                        if (!methodNodeObj.has("description")) {
                            //if no description is set, set description = summary
                            JsonNode summaryNode = methodNodeObj.path("summary");
                            if (summaryNode.isMissingNode()) {
                                LOG.warn("Skipping description update for node: summary not found for operationId = {}",
                                        methodNodeObj.path("operationId").asText());
                            } else if (summaryNode.isNull()) {
                                LOG.warn("Skipping description update for node: summary is null for operationId = {}",
                                        methodNodeObj.path("operationId").asText());
                            } else {
                                methodNodeObj.put("description", summaryNode.asText());
                            }
                        }
                    }
                }
            }
        }
    }

    private void addTagsDescriptions(JsonNode spec) {
        JsonNode defaultTags = null;
        try (InputStream in = ResourceUtils.getResourceAsStream("tagDescriptions.json")) {
            defaultTags = MAPPER.readTree(in);
        } catch (IOException e) {
            LOG.error("Failed to read tagDescriptions.json from resources");
            throw new RuntimeException(e);
        }
        JsonNode tagsNode = spec.path("tags");
        if (tagsNode.isNull() || tagsNode.isMissingNode()) {
            ((ObjectNode) spec).set("tags", defaultTags.path("tags"));
        }
    }
}
