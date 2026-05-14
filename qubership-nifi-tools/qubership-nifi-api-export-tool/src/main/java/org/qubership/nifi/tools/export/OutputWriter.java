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

package org.qubership.nifi.tools.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Writes component descriptor JSON files to subdirectories of the output directory.
 * Output structure: {@code <outputDir>/{processors,controllerService,reportingTask}/<SimpleName>.json}
 */
public final class OutputWriter {

    private static final Logger LOG = LoggerFactory.getLogger(OutputWriter.class);

    private final String outputDir;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a new OutputWriter targeting the given output directory.
     *
     * @param dir the root output directory path
     */
    public OutputWriter(final String dir) {
        this.outputDir = dir;
    }

    /**
     * Writes all component descriptors for the given kind to JSON files.
     *
     * @param kind       the component kind (determines sub-directory)
     * @param components list of maps with "type" and "propertyDescriptors" entries
     * @throws IOException if the output directory cannot be created
     */
    public void write(final ComponentKind kind, final List<Map<String, Object>> components) throws IOException {
        File dir = new File(outputDir, kind.getOutputDirName());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create output directory: " + dir.getAbsolutePath());
        }

        int written = 0;
        for (Map<String, Object> component : components) {
            String fqcn = (String) component.get("type");
            JsonNode descriptors = (JsonNode) component.get("propertyDescriptors");

            String simpleName = simpleName(fqcn);
            File outputFile = new File(dir, simpleName + ".json");

            try {
                ObjectNode output = mapper.createObjectNode();
                output.put("type", fqcn);
                output.set("propertyDescriptors", descriptors);
                mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
                LOG.debug("Written: {}", outputFile.getPath());
                written++;
            } catch (Exception e) {
                LOG.warn("Failed to write output for {}", fqcn, e);
            }
        }

        LOG.info("Wrote {} files to {}", written, dir.getPath());
    }

    private String simpleName(final String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }
}
