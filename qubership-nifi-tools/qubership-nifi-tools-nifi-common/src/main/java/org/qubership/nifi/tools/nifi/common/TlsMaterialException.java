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

package org.qubership.nifi.tools.nifi.common;

/**
 * Signals that TLS key or trust material is missing, unreadable, malformed, or otherwise unusable.
 * Messages never contain passwords or private-key material.
 */
public class TlsMaterialException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message the human-readable reason
     */
    public TlsMaterialException(final String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the human-readable reason
     * @param cause   the underlying cause
     */
    public TlsMaterialException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
