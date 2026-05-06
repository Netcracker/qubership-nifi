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
package org.qubership.nifi.tools.openapi.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mainEnrichesAndWritesOpenApiJson(@TempDir Path tempDir) throws Exception {
        Main.main(new String[]{"--output-dir", tempDir.toString()});

        Path output = tempDir.resolve("openapi.json");
        assertTrue(Files.exists(output));

        JsonNode result = MAPPER.readTree(output.toFile());
        assertNotNull(result);

        assertEquals("/nifi-api", result.path("servers").get(0).path("url").asText());
        assertEquals(28, result.path("tags").size());

        JsonNode get = result.path("paths").path("/nifi-api/resources").path("get");
        assertEquals("Gets the resources", get.path("description").asText());
        assertEquals("successful operation", get.path("responses").path("200").path("description").asText());
    }

    @Test
    void mainCreatesOutputDirectoryIfNotExists(@TempDir Path tempDir) throws Exception {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");
        assertFalse(Files.exists(nested));

        Main.main(new String[]{"--output-dir", nested.toString()});

        assertTrue(Files.exists(nested));
        assertTrue(Files.exists(nested.resolve("openapi.json")));
    }

    @Test
    void mainUnknownFlagsAreIgnored(@TempDir Path tempDir) throws Exception {
        Main.main(new String[]{"--unknown-flag", "value", "--output-dir", tempDir.toString()});
        assertTrue(Files.exists(tempDir.resolve("openapi.json")));
    }

    @Test
    void mainMissingOutputDirValueThrowsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Main.main(new String[]{"--output-dir"}));
        assertTrue(ex.getMessage().contains("--output-dir requires a value"));
    }
}
