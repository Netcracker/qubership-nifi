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
 * Adds request authentication to outgoing NiFi requests without coupling the transport layer to a
 * specific credential source. Implementations must never expose secret values through logging or
 * diagnostics.
 */
public interface NiFiRequestAuthenticator {

    /**
     * Applies authentication headers to the given request builder.
     *
     * @param builder the request builder to mutate
     */
    void apply(HttpRequest.Builder builder);
}
