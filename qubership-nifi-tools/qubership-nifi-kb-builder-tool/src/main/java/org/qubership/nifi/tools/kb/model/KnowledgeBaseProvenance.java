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

package org.qubership.nifi.tools.kb.model;

import java.time.Instant;

/**
 * Builder and NiFi provenance recorded in the Knowledge Base manifest.
 *
 * @param builderName             the builder name
 * @param builderVersion          the builder version
 * @param generatedAt             the generation timestamp
 * @param nifiVersion             the detected NiFi version
 * @param minimumSupportedVersion the minimum supported NiFi version
 * @param baseUrl                 the normalized NiFi base URL
 */
public record KnowledgeBaseProvenance(String builderName, String builderVersion, Instant generatedAt,
                                      String nifiVersion, String minimumSupportedVersion, String baseUrl) {
}
