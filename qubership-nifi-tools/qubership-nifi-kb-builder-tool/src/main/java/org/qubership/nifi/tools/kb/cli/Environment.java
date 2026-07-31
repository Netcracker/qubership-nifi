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
 * Reads environment variables. Abstracted so tests can supply secrets without touching the real
 * process environment.
 */
@FunctionalInterface
public interface Environment {

    /** The token environment variable name. */
    String NIFI_ACCESS_TOKEN = "NIFI_ACCESS_TOKEN";

    /** The authorization bearer cookie environment variable name. */
    String NIFI_AUTHORIZATION_BEARER_COOKIE = "NIFI_AUTHORIZATION_BEARER_COOKIE";

    /** The certificate password environment variable name. */
    String NIFI_PKCS12_PASSWORD = "NIFI_PKCS12_PASSWORD";

    /**
     * Returns the value of the named environment variable, or {@code null} when unset.
     *
     * @param name the variable name
     * @return the value, or {@code null}
     */
    String get(String name);

    /**
     * Returns an environment backed by the real process environment.
     *
     * @return the system environment
     */
    static Environment system() {
        return System::getenv;
    }
}
