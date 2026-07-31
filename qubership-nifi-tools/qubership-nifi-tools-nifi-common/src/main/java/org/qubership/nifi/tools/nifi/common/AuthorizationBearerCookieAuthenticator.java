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

import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * Adds NiFi's {@code __Secure-Authorization-Bearer} session cookie. The cookie value is held only
 * in memory and is never included in {@link #toString()} or any diagnostic output.
 */
public final class AuthorizationBearerCookieAuthenticator implements NiFiRequestAuthenticator {

    /** The name of the HTTP header carrying cookies. */
    public static final String COOKIE_HEADER = "Cookie";

    /** The name of NiFi's authorization bearer cookie. */
    public static final String COOKIE_NAME = "__Secure-Authorization-Bearer";

    private final String value;

    /**
     * Creates a new authorization bearer cookie authenticator.
     *
     * @param cookieValue the cookie value to present; must be non-blank
     */
    public AuthorizationBearerCookieAuthenticator(final String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            throw new IllegalArgumentException("Authorization bearer cookie must not be blank");
        }
        this.value = cookieValue;
    }

    @Override
    public void apply(final HttpRequest.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.header(COOKIE_HEADER, COOKIE_NAME + "=" + value);
    }

    @Override
    public String toString() {
        return "AuthorizationBearerCookieAuthenticator{cookie=<redacted>}";
    }
}
