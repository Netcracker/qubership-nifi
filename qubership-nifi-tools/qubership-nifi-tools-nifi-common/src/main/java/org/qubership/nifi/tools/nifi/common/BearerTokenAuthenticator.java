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
 * Adds an {@code Authorization: Bearer} header carrying an access token. The token is held only in
 * memory and is never included in {@link #toString()} or any diagnostic output.
 */
public final class BearerTokenAuthenticator implements NiFiRequestAuthenticator {

    /** The name of the HTTP header carrying the bearer credential. */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    private final String token;

    /**
     * Creates a new bearer-token authenticator.
     *
     * @param accessToken the final access token to present; must be non-blank
     */
    public BearerTokenAuthenticator(final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Bearer access token must not be blank");
        }
        this.token = accessToken;
    }

    @Override
    public void apply(final HttpRequest.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.header(AUTHORIZATION_HEADER, "Bearer " + token);
    }

    @Override
    public String toString() {
        return "BearerTokenAuthenticator{token=<redacted>}";
    }
}
