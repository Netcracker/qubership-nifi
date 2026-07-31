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

/**
 * The guide collection mode recorded in the manifest.
 */
public enum GuideMode {

    /** Guides are collected and required (the default). */
    REQUIRED("required", "collected"),

    /** Guides are intentionally skipped via {@code --skip-guides}. */
    SKIP("skip", "skipped");

    private final String manifestMode;
    private final String manifestStatus;

    GuideMode(final String mode, final String status) {
        this.manifestMode = mode;
        this.manifestStatus = status;
    }

    /**
     * Returns the manifest {@code guides.mode} value.
     *
     * @return the mode value
     */
    public String manifestMode() {
        return manifestMode;
    }

    /**
     * Returns the manifest per-guide status value for this mode.
     *
     * @return the status value
     */
    public String manifestStatus() {
        return manifestStatus;
    }
}
