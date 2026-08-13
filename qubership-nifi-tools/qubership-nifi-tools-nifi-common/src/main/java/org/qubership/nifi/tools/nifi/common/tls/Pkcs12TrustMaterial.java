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

package org.qubership.nifi.tools.nifi.common.tls;

import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Trust material backed by a PKCS#12 trust store supplied as raw bytes rather than as a file path,
 * for a store that is only available in memory.
 */
public final class Pkcs12TrustMaterial extends AbstractPkcs12Material implements TrustMaterial {

    private Pkcs12TrustMaterial(final byte[] bytes, final char[] password) {
        super(bytes, password);
    }

    /**
     * Creates trust material from PKCS#12 bytes and a password. The supplied arrays are copied; the
     * caller remains responsible for clearing its own copies.
     *
     * @param bytes    the PKCS#12 trust-store bytes
     * @param password the trust-store password
     * @return the trust material
     */
    public static Pkcs12TrustMaterial fromBytes(final byte[] bytes, final char[] password) {
        return new Pkcs12TrustMaterial(bytes, password);
    }

    @Override
    public TrustManager[] trustManagers() {
        try {
            final KeyStore trustStore = loadKeyStore();
            return TlsMaterialUtils.createTrustManagers(trustStore);
        } catch (final GeneralSecurityException | IOException e) {
            throw new TlsMaterialException("PKCS#12 trust store could not be loaded", e);
        }
    }
}
