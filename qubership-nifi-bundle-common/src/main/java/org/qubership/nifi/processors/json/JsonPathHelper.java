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

package org.qubership.nifi.processors.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import org.qubership.nifi.processors.json.exception.KeyNodeNotExistsException;
import org.qubership.nifi.processors.json.exception.NodeToInsertNotFoundException;
import org.qubership.nifi.processors.json.context.JsonMergeContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.jayway.jsonpath.Option.ALWAYS_RETURN_LIST;

public class JsonPathHelper {

    public static final Configuration JACKSON_ALL_AS_LIST_CONFIGURATION = Configuration.builder()
            .options(ALWAYS_RETURN_LIST)
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .build();

    public static final Configuration DEFAULT_JACKSON_CONFIGURATION = Configuration.defaultConfiguration()
            .jsonProvider(new JacksonJsonNodeJsonProvider());

    public static final Configuration CONFIGURATION_SUPPRESS_EXCEPTIONS = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .build();

    public static final Configuration CONFIGURATION_GET_PATH_LIST_OF_ATTRIBUTE = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(Option.AS_PATH_LIST)
            .build();

    private static final String JSON_PATH_DELIMITER = ".";

    private final DocumentContext json;

    public DocumentContext getJson(){
        return json;
    }

    public JsonPathHelper(JsonNode input) {
        this.json =
                JsonPath.parse(
                        input,
                        JACKSON_ALL_AS_LIST_CONFIGURATION
                );
    }

    public JsonPathHelper(JsonNode input, Configuration configuration) {
        this.json =
                JsonPath.parse(
                        input,
                        configuration
                );
    }

    public List<String> extractValuesByKey(String path, String key) {
        ArrayNode resultNodes = json.read(path + JSON_PATH_DELIMITER + key);
        List<String> result = new ArrayList<>(resultNodes.size());

        for (JsonNode node : resultNodes) {
            result.add(node.asText());
        }

        return result;
    }

    public List<String> extractValuesByKey(String path) {
        ArrayNode resultNodes = json.read(path);
        List<String> result = new ArrayList<>(resultNodes.size());

        for (JsonNode node : resultNodes) {
            result.add(node.asText());
        }

        return result;
    }

    public void merge(JsonMergeContext context) throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        if (isValuesPresent(context.getNodes())) return;

        if (StringUtils.isBlank(context.getPathToInsert())) {
            Multimap<String, JsonNode> source =
                    convertObjectNodesToMap(
                            context.getKeyFromSourceToTarget(),
                            readNodesByPath(context.getPath())
                    );

            mergeValues(context, source);
        } else {
            mergeValues(context);
        }
    }

    private boolean isValuesPresent(ArrayNode array) {
        return array == null || array.size() == 0;
    }

    public Multimap<String, JsonNode> convertObjectNodesToMap(
            String key,
            ArrayNode nodes
    ) throws KeyNodeNotExistsException {
        Multimap<String, JsonNode> result = ArrayListMultimap.create();

        for (JsonNode node : nodes) {
            result.put(extractNodeByKey(key, node).asText(), node);
        }

        return result;
    }

    private JsonNode extractNodeByKey(String key, JsonNode node) throws KeyNodeNotExistsException {
        JsonNode result = node.get(key);

        if (result == null)
            throw new KeyNodeNotExistsException("A key was not found: " + key);

        return result;
    }

    public ArrayNode readNodesByPath(String path) throws NodeToInsertNotFoundException {
        ArrayNode result = json.read(path);

        if (result.size() == 0)
            throw new NodeToInsertNotFoundException("The path to objects is wrong: + " + path);

        return result;
    }

    private void mergeValues(JsonMergeContext context, Multimap<String, JsonNode> source) throws KeyNodeNotExistsException {
        for (JsonNode value : context.getNodes()) {
            validateValue(value);

            JsonNode sourceIdNode = extractNodeByKey(context.getKeyFromTargetToSource(), value);
            Collection<JsonNode> sourceNodes = extractNodeByKey(sourceIdNode.asText(), source);

            mergeSingleValueToSource(context, value, sourceNodes);

            if (context.isNeedToCleanTarget() && value.isObject())
                ((ObjectNode) value).remove(context.getKeyFromTargetToSource());
        }
    }

    private void validateValue(JsonNode value) {
        if (!value.isObject()) throw new IllegalArgumentException("A value must be a json object.");
    }

    private Collection<JsonNode> extractNodeByKey(
            String key,
            Multimap<String, JsonNode> source
    ) throws KeyNodeNotExistsException {
        Collection<JsonNode> result = source.get(key);

        if (result == null || result.isEmpty())
            throw new KeyNodeNotExistsException("A parent node by key was not found: " + key);

        //remove duplicates:
        //if we merge the same value to multiple locations it will be represented by single JsonNode
        //so we need to add children to it only once, and they will be added in all places
        List<JsonNode> newResult = new ArrayList<JsonNode>();
        for ( JsonNode res : result){
            if (newResult.isEmpty()){
                newResult.add(res);
            } else {
                boolean addItem = true;
                for (JsonNode newRes : newResult){
                    if (newRes == res){
                        addItem = false;
                        break;
                    }
                }
                if (addItem){
                    newResult.add(res);
                }
            }
        }

        return newResult;
    }

    private void mergeSingleValueToSource(JsonMergeContext context, JsonNode value, Collection<JsonNode> sourceNodes) {
        for (JsonNode node : sourceNodes) {
            if (node.isObject())
                if (context.isArray())
                    ((ObjectNode) node).withArray(context.getKeyToInsertTarget()).add(value);
                else
                    ((ObjectNode) node).set(context.getKeyToInsertTarget(), value);
        }
    }

    private void mergeValues(JsonMergeContext context) throws NodeToInsertNotFoundException {
        final ArrayNode nodesInWhichInsert = readNodesByPath(context.getPathToInsert());

        context
                .getNodes()
                .forEach(
                        value -> nodesInWhichInsert.forEach(
                                node -> {
                                    if (node.isObject())
                                        if (context.isArray())
                                            ((ObjectNode) node).withArray(context.getKeyToInsertTarget()).add(value);
                                        else
                                            ((ObjectNode) node).set(context.getKeyToInsertTarget(), value);
                                    else if (node.isArray())
                                        ((ArrayNode) node).add(value);
                                }
                        )
                );
    }

    public void cleanUp(String path, String key) {
        json.delete(path + JSON_PATH_DELIMITER + key);
    }

    public JsonNode getJsonNode() {
        return json.json();
    }
}
