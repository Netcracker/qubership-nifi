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
 * The explicitly selected authentication mode.
 */
public enum AuthMode {

    /** Bearer access token from {@code NIFI_ACCESS_TOKEN}. */
    TOKEN,

    /** NiFi authorization bearer cookie from {@code NIFI_AUTHORIZATION_BEARER_COOKIE}. */
    COOKIE,

    /** Mutual TLS with a PKCS#12 client certificate. */
    CERTIFICATE;

    /**
     * Parses the authentication mode from its command-line value.
     *
     * @param value the {@code --auth} value
     * @return the parsed mode
     * @throws ConfigurationException when the value is not a known mode
     */
    public static AuthMode parse(final String value) {
        if ("token".equals(value)) {
            return TOKEN;
        }
        if ("cookie".equals(value)) {
            return COOKIE;
        }
        if ("certificate".equals(value)) {
            return CERTIFICATE;
        }
        throw new ConfigurationException(
                "--auth must be 'token', 'cookie', or 'certificate', but was: " + value);
    }
}
