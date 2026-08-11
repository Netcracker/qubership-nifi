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

/**
 * A no-op authenticator that leaves HTTP authentication headers unset. It is used for mutual-TLS
 * authentication, where the client certificate carries identity, and for unauthenticated bootstrap
 * requests such as username/password token acquisition.
 */
public final class NoAuthentication implements NiFiRequestAuthenticator {

    /** Shared stateless instance. */
    public static final NoAuthentication INSTANCE = new NoAuthentication();

    /**
     * Creates a new no-op authenticator. Prefer {@link #INSTANCE} for the shared stateless value.
     */
    public NoAuthentication() {
        // no state
    }

    @Override
    public void apply(final HttpRequest.Builder builder) {
        // Intentionally leaves authentication headers unset.
    }
}
