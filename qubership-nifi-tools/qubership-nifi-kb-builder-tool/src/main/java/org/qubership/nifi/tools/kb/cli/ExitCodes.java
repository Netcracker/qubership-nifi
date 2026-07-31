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

package org.qubership.nifi.tools.kb.cli;

/**
 * Process exit codes by failure category.
 */
public final class ExitCodes {

    /** Successful completion. */
    public static final int SUCCESS = 0;

    /** Usage or configuration error. */
    public static final int USAGE = 2;

    /** TLS or authentication failure. */
    public static final int AUTH = 3;

    /** Authorization failure. */
    public static final int AUTHORIZATION = 4;

    /** Unsupported target NiFi version. */
    public static final int UNSUPPORTED_TARGET = 5;

    /** Collection or parsing failure. */
    public static final int COLLECTION = 6;

    /** Output staging, validation, or replacement failure. */
    public static final int OUTPUT = 7;

    private ExitCodes() {
        // constants holder
    }
}
