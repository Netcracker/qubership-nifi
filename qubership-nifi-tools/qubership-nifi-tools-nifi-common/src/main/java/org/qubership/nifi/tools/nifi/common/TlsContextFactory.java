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

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Builds {@link SSLContext} instances from optional client key material and optional trust
 * material. When trust material is absent the JVM default trust managers are used. Hostname
 * verification is a property of the HTTP client and remains enabled; this factory never disables
 * trust or hostname checks.
 */
public final class TlsContextFactory {

    private static final String TLS = "TLS";

    private TlsContextFactory() {
        // utility class
    }

    /**
     * Builds an SSL context.
     *
     * @param keyMaterial   optional client key material for mutual TLS
     * @param trustMaterial optional trust material; when empty, the JVM default trust store is used
     * @return the initialized SSL context
     * @throws TlsMaterialException when the context cannot be initialized
     */
    public static SSLContext create(final Optional<ClientKeyMaterial> keyMaterial,
                                    final Optional<TrustMaterial> trustMaterial) {
        try {
            final KeyManager[] keyManagers = keyMaterial.map(ClientKeyMaterial::keyManagers).orElse(null);
            final TrustManager[] trustManagers = trustMaterial.map(TrustMaterial::trustManagers).orElse(null);
            final SSLContext context = SSLContext.getInstance(TLS);
            context.init(keyManagers, trustManagers, new SecureRandom());
            return context;
        } catch (final GeneralSecurityException e) {
            throw new TlsMaterialException("TLS context could not be initialized", e);
        }
    }
}
