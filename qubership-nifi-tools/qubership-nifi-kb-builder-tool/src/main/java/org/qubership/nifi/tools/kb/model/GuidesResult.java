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
import java.util.Optional;

/**
 * The outcome of guide collection: the guide mode and, in required mode, the collected guide
 * documents. In skip mode the document list is empty.
 */
public final class GuidesResult {

    private final GuideMode mode;
    private final List<GuideDocument> documents;

    /**
     * Creates a new guides result.
     *
     * @param guideMode      the guide mode
     * @param guideDocuments the collected guide documents (empty in skip mode)
     */
    public GuidesResult(final GuideMode guideMode, final List<GuideDocument> guideDocuments) {
        this.mode = guideMode;
        this.documents = List.copyOf(guideDocuments);
    }

    /**
     * Returns the guide mode.
     *
     * @return the mode
     */
    public GuideMode mode() {
        return mode;
    }

    /**
     * Returns the collected guide documents.
     *
     * @return the documents, as an immutable list, empty in skip mode
     */
    public List<GuideDocument> documents() {
        return documents;
    }

    /**
     * Finds a collected guide document by type.
     *
     * @param type the guide type
     * @return the matching document, if present
     */
    public Optional<GuideDocument> find(final GuideType type) {
        return documents.stream().filter(doc -> doc.type() == type).findFirst();
    }
}
