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

package org.qubership.nifi.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.processors.json.JsonPathHelper;
import org.qubership.nifi.processors.json.context.InsertionContext;
import org.qubership.nifi.processors.json.context.JsonMergeContext;
import org.qubership.nifi.processors.json.exception.KeyNodeNotExistsException;
import org.qubership.nifi.processors.json.exception.NodeToInsertNotFoundException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class JsonPathHelperTest {

    private static final String DEFAULT_PATH = "$[*]";
    private static final String ID_KEY = "id";
    private static final String PARENT_ID_KEY = "parentId";
    private static final String NAME_KEY = "name";
    private static final String DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET = ID_KEY;
    private static final String DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE = PARENT_ID_KEY;
    private static final String DEFAULT_KEY_TO_INSERT = "products";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void extractOneObject() throws IOException, NodeToInsertNotFoundException, KeyNodeNotExistsException {
        JsonNode input = MAPPER.readTree("""
                [
                    {
                        "id": "1",
                        "name": "Customer1"
                    }
                ]""");

        Map<String, List<JsonNode>> expected = new HashMap<>();
        JsonNode expectedObject = input.get(0);
        expected.put(expectedObject.get(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET).asText(),
                Collections.singletonList(expectedObject));

        JsonPathHelper helper = new JsonPathHelper(input);
        Assertions.assertEquals(
                expected,
                helper.convertObjectNodesToMap(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET,
                        helper.readNodesByPath(DEFAULT_PATH)).asMap()
        );
    }

    @Test
    public void extractTwoObjects() throws IOException, KeyNodeNotExistsException, NodeToInsertNotFoundException {
        JsonNode input = MAPPER.readTree("""
                [
                    {
                        "id": "1",
                        "name": "Customer1"
                    },
                    {
                        "id": "2",
                        "name": "Customer2"
                    }
                ]""");

        Map<String, List<JsonNode>> expected = new HashMap<>();
        expected.put(input.get(0).get(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET).asText(),
                Collections.singletonList(input.get(0)));
        expected.put(input.get(1).get(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET).asText(),
                Collections.singletonList(input.get(1)));

        JsonPathHelper helper = new JsonPathHelper(input);
        Assertions.assertEquals(
                expected,
                helper.convertObjectNodesToMap(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET,
                        helper.readNodesByPath(DEFAULT_PATH)).asMap()
        );
    }

    @Test
    public void testSourceArrayWithOneObjectAndEmptyValue()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ArrayNode expected = JsonNodeFactory.instance.arrayNode().add(createContact(1, "Contact1"));

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .build()
                        )
                        .nodes(JsonNodeFactory.instance.arrayNode()
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(
                JsonNodeFactory.instance.arrayNode().add(createContact(1, "Contact1"))
        );
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }

    private ObjectNode createContact(int id, String name) {
        return JsonNodeFactory.instance
                .objectNode()
                .put(ID_KEY, id)
                .put(NAME_KEY, name);
    }

    private ObjectNode createProduct(int id, int parentId, String name) {
        return JsonNodeFactory.instance
                .objectNode()
                .put(ID_KEY, id)
                .put(PARENT_ID_KEY, parentId)
                .put(NAME_KEY, name);
    }

    @Test
    public void testSourceArrayWithOneObjectAndOneValue()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ObjectNode childNode = createProduct(11, 1, "Product11");

        ArrayNode expected = JsonNodeFactory.instance.arrayNode().add(
                createContact(1, "Contact1")
                        .set(
                                DEFAULT_KEY_TO_INSERT,
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNode)
                        )
        );

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNode)
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(JsonNodeFactory.instance.arrayNode().
                add(createContact(1, "Contact1")));
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }

    @Test
    public void testSourceArrayWithOneObjectAndTwoValues()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ObjectNode childNodeOne = createProduct(11, 1, "Product11");
        ObjectNode childNodeTwo = createProduct(22, 1, "Product22");

        ArrayNode expected = JsonNodeFactory.instance.arrayNode().add(
                createContact(1, "Contact1")
                        .set(
                                DEFAULT_KEY_TO_INSERT,
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNodeOne)
                                        .add(childNodeTwo)
                        )
        );

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNodeOne)
                                        .add(childNodeTwo)
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(JsonNodeFactory.instance.arrayNode().
                add(createContact(1, "Contact1")));
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }

    @Test
    public void testSourceArrayWithTwoObjectsAndFourValues()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ObjectNode childNodeOne = createProduct(11, 1, "Product11");
        ObjectNode childNodeTwo = createProduct(22, 1, "Product22");
        ObjectNode childNodeThree = createProduct(33, 2, "Product33");
        ObjectNode childNodeFour = createProduct(44, 2, "Product44");

        ArrayNode expected = JsonNodeFactory.instance.arrayNode()
                .add(
                        createContact(1, "Contact1")
                                .set(
                                        DEFAULT_KEY_TO_INSERT,
                                        JsonNodeFactory.instance
                                                .arrayNode()
                                                .add(childNodeOne)
                                                .add(childNodeTwo)
                                )
                )
                .add(
                        createContact(2, "Contact2")
                                .set(
                                        DEFAULT_KEY_TO_INSERT,
                                        JsonNodeFactory.instance
                                                .arrayNode()
                                                .add(childNodeThree)
                                                .add(childNodeFour)
                                )
                );

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNodeOne)
                                        .add(childNodeTwo)
                                        .add(childNodeThree)
                                        .add(childNodeFour)
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(
                JsonNodeFactory.instance.arrayNode()
                        .add(createContact(1, "Contact1"))
                        .add(createContact(2, "Contact2"))
        );
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }

    @Test
    public void testExtractOneIdByPath() {
        List<String> actual = new JsonPathHelper(
                JsonNodeFactory.instance.arrayNode().add(
                        createContact(1, "Contact1")
                )
        ).extractValuesByKey(DEFAULT_PATH, DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET);

        Assertions.assertIterableEquals(
                Collections.singletonList("1"),
                actual
        );
    }

    @Test
    public void testExtractSeveralIdsByPath() {
        List<String> actual = new JsonPathHelper(
                JsonNodeFactory.instance.arrayNode()
                        .add(createContact(1, "Contact1"))
                        .add(createContact(2, "Contact2"))
                        .add(createContact(3, "Contact3"))
                        .add(createContact(4, "Contact4"))
        ).extractValuesByKey(DEFAULT_PATH, DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET);

        Assertions.assertIterableEquals(
                Arrays.asList("1", "2", "3", "4"),
                actual
        );
    }

    @Test
    public void testMergeWhenParentRefersToChild() throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        String keyToJoinSourceWithTarget = "productId";
        String keyToJoinTargetWithSource = "id";
        String keyToInsert = "products";

        JsonNode childNode = createProduct(11, 1, "Product11");

        JsonPathHelper helper = new JsonPathHelper(JsonNodeFactory.instance.arrayNode()
                .add(createContact(1, "Contact1").put(keyToJoinSourceWithTarget, 11)));

        helper.merge(
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(keyToJoinSourceWithTarget)
                                        .joinKeyChildWithParent(keyToJoinTargetWithSource)
                                        .keyToInsert(keyToInsert)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNode)
                        )
                        .build()
        );

        JsonNode expected = JsonNodeFactory.instance.arrayNode()
                .add(
                        createContact(1, "Contact1")
                                .put(keyToJoinSourceWithTarget, 11)
                                .set(
                                        keyToInsert,
                                        JsonNodeFactory.instance.arrayNode().add(childNode)
                                )
                );

        Assertions.assertEquals(
                expected,
                helper.getJsonNode()
        );
    }

    @Test
    public void testMergeWhenParentRefersToChildCleanUpChildKey()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ObjectNode expectedChild = createProduct(11, 1, "Product11");
        expectedChild.remove(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE);

        ArrayNode expected = JsonNodeFactory.instance.arrayNode().add(
                createContact(1, "Contact1")
                        .set(
                                DEFAULT_KEY_TO_INSERT,
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(expectedChild)
                        )
        );

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .isNeedToCleanTarget(true)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(createProduct(11, 1, "Product11"))
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(JsonNodeFactory.instance.arrayNode().
                add(createContact(1, "Contact1")));
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }

    @Test
    public void testKeyNotFoundDuringExtract() {
        assertThrows(KeyNodeNotExistsException.class, () -> {
            JsonPathHelper helper = new JsonPathHelper(
                    JsonNodeFactory.instance.arrayNode().add(createContact(1, "Contact1"))
            );

            helper.convertObjectNodesToMap("fakeId", helper.readNodesByPath(DEFAULT_PATH));
        });

    }

    @Test
    public void extractZeroObjects() {
        assertThrows(NodeToInsertNotFoundException.class, () -> {
            ArrayNode emptyInput = JsonNodeFactory.instance.arrayNode();
            JsonPathHelper helper = new JsonPathHelper(emptyInput);
            helper.convertObjectNodesToMap(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET,
                    helper.readNodesByPath(DEFAULT_PATH));

        });
    }

    @Test
    public void testSourceKeyNotFoundDuringMerge() {
        assertThrows(KeyNodeNotExistsException.class, () -> {
            JsonPathHelper helper = new JsonPathHelper(
                    JsonNodeFactory.instance.arrayNode().add(createContact(1, "Contact1"))
            );

            helper.merge(
                    JsonMergeContext
                            .builder()
                            .path(DEFAULT_PATH)
                            .insertionContext(
                                    InsertionContext
                                            .builder()
                                            .joinKeyParentWithChild("fakeSourceKey")
                                            .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                            .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                            .build()
                            )
                            .nodes(
                                    JsonNodeFactory.instance
                                            .arrayNode()
                                            .add(createProduct(11, 1, "Product11"))
                            )
                            .build()
            );
        });
    }

    @Test
    public void testTargetKeyNotFoundDuringMerge() {
        assertThrows(KeyNodeNotExistsException.class, () -> {
            JsonPathHelper helper = new JsonPathHelper(
                    JsonNodeFactory.instance.arrayNode().add(createContact(1, "Contact1"))
            );

            helper.merge(
                    JsonMergeContext
                            .builder()
                            .path(DEFAULT_PATH)
                            .insertionContext(
                                    InsertionContext
                                            .builder()
                                            .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                            .joinKeyChildWithParent("fakeTargetKey")
                                            .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                            .build()
                            )
                            .nodes(
                                    JsonNodeFactory.instance
                                            .arrayNode()
                                            .add(createProduct(11, 1, "Product11"))
                            )
                            .build()
            );
        });
    }

    @Test
    public void testSourceArrayWithOneObjectAndOneValueAndCustomPath()
            throws NodeToInsertNotFoundException, KeyNodeNotExistsException {
        ObjectNode childNode = createProduct(11, 1, "Product11");

        ArrayNode expected = JsonNodeFactory.instance
                .arrayNode()
                .add(createContact(1, "Contact1"))
                .add(childNode);

        JsonMergeContext context =
                JsonMergeContext
                        .builder()
                        .path(DEFAULT_PATH)
                        .pathToInsert("$")
                        .insertionContext(
                                InsertionContext
                                        .builder()
                                        .joinKeyParentWithChild(DEFAULT_KEY_TO_JOIN_SOURCE_WITH_TARGET)
                                        .joinKeyChildWithParent(DEFAULT_KEY_TO_JOIN_TARGET_WITH_SOURCE)
                                        .keyToInsert(DEFAULT_KEY_TO_INSERT)
                                        .build()
                        )
                        .nodes(
                                JsonNodeFactory.instance
                                        .arrayNode()
                                        .add(childNode)
                        )
                        .build();

        JsonPathHelper extractor = new JsonPathHelper(JsonNodeFactory.instance.arrayNode().
                add(createContact(1, "Contact1")));
        extractor.merge(context);

        Assertions.assertEquals(
                expected,
                extractor.getJsonNode()
        );
    }
}
