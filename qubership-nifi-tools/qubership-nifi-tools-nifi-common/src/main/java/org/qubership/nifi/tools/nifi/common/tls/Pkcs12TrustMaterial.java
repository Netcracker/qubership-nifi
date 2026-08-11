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
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Trust material backed by a PKCS#12 trust store supplied as raw bytes rather than as a file path,
 * for a store that is only available in memory.
 */
public final class Pkcs12TrustMaterial implements TrustMaterial {

    private static final String PKCS12 = "PKCS12";

    private final byte[] storeBytes;
    private final char[] storePassword;

    private Pkcs12TrustMaterial(final byte[] bytes, final char[] password) {
        this.storeBytes = bytes.clone();
        this.storePassword = password.clone();
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
            final KeyStore trustStore = KeyStore.getInstance(PKCS12);
            try (InputStream in = new ByteArrayInputStream(storeBytes)) {
                trustStore.load(in, storePassword);
            }
            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        } catch (final GeneralSecurityException | IOException e) {
            throw new TlsMaterialException("PKCS#12 trust store could not be loaded", e);
        }
    }

    /**
     * Clears the retained password characters. Call after the SSL context has been initialized.
     */
    public void clearPassword() {
        Arrays.fill(storePassword, '\0');
    }
}
