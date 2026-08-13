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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Retains cloned PKCS#12 bytes and password characters for TLS material implementations.
 */
abstract class AbstractPkcs12Material {

    private static final String PKCS12 = "PKCS12";

    private final byte[] storeBytes;
    private final char[] storePassword;

    AbstractPkcs12Material(final byte[] bytes, final char[] password) {
        this.storeBytes = bytes.clone();
        this.storePassword = password.clone();
    }

    final KeyStore loadKeyStore() throws GeneralSecurityException, IOException {
        final KeyStore keyStore = KeyStore.getInstance(PKCS12);
        try (InputStream input = new ByteArrayInputStream(storeBytes)) {
            keyStore.load(input, storePassword);
        }
        return keyStore;
    }

    final char[] password() {
        return storePassword;
    }

    /**
     * Clears the retained password characters. Call after the SSL context has been initialized.
     */
    public final void clearPassword() {
        Arrays.fill(storePassword, '\0');
    }
}
