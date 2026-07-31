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

import java.util.Optional;

/**
 * The derived additional-documentation state of a component: whether the definition advertised
 * additional details, whether the tool requested them, whether they were available, and the
 * relative file path when available.
 */
public final class AdditionalDocumentationState {

    /** The relative file name used for a component's verbatim additional documentation. */
    public static final String ADDITIONAL_DETAILS_FILE = "additionalDetails.md";

    private final boolean advertised;
    private final boolean requested;
    private final boolean available;

    private AdditionalDocumentationState(final boolean isAdvertised, final boolean isRequested,
                                         final boolean isAvailable) {
        this.advertised = isAdvertised;
        this.requested = isRequested;
        this.available = isAvailable;
    }

    /**
     * Returns the state for a component whose definition did not advertise additional details.
     *
     * @return the not-advertised state
     */
    public static AdditionalDocumentationState notAdvertised() {
        return new AdditionalDocumentationState(false, false, false);
    }

    /**
     * Returns the state for a component that advertised additional details but whose request
     * returned no document (for example a {@code 404}).
     *
     * @return the advertised-but-unavailable state
     */
    public static AdditionalDocumentationState advertisedUnavailable() {
        return new AdditionalDocumentationState(true, true, false);
    }

    /**
     * Returns the state for a component that advertised additional details and received a document.
     *
     * @return the advertised-and-available state
     */
    public static AdditionalDocumentationState advertisedAvailable() {
        return new AdditionalDocumentationState(true, true, true);
    }

    /**
     * Reports whether the definition advertised additional details.
     *
     * @return {@code true} when advertised
     */
    public boolean isAdvertised() {
        return advertised;
    }

    /**
     * Reports whether the tool requested additional details.
     *
     * @return {@code true} when requested
     */
    public boolean isRequested() {
        return requested;
    }

    /**
     * Reports whether additional details were available and written.
     *
     * @return {@code true} when available
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the relative path to the additional-details file, present only when available.
     *
     * @return the optional relative path
     */
    public Optional<String> path() {
        return available ? Optional.of(ADDITIONAL_DETAILS_FILE) : Optional.empty();
    }
}
