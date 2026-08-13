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

import java.util.List;

/**
 * A collected and converted guide.
 *
 * @param type             the guide type
 * @param markdown         the converted Markdown content, including the generated provenance header
 * @param sourceUrl        the redacted source URL the guide was fetched from
 * @param contentType      the fetched content type
 * @param selectedHeadings the selected top-level headings (used for the Developer's Guide)
 */
public record GuideDocument(GuideType type, String markdown, String sourceUrl, String contentType,
                            List<String> selectedHeadings) {
}
