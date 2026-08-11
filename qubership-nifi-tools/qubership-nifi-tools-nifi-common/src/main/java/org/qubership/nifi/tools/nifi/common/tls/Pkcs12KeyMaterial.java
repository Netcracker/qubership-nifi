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

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Enumeration;

/**
 * Client key material loaded from a PKCS#12 file. The file must contain exactly one usable
 * private-key entry with its certificate chain; startup fails on multiple usable entries rather
 * than relying on provider-dependent alias selection.
 */
public final class Pkcs12KeyMaterial extends AbstractPkcs12Material implements ClientKeyMaterial {

    private Pkcs12KeyMaterial(final byte[] bytes, final char[] password) {
        super(bytes, password);
    }

    /**
     * Loads client key material from a PKCS#12 file.
     *
     * @param pkcs12File the path to the PKCS#12 file
     * @param password   the file password; the supplied array is copied and not retained by the caller's reference
     * @return the key material
     * @throws TlsMaterialException when the file is missing or unreadable
     */
    public static Pkcs12KeyMaterial fromFile(final Path pkcs12File, final char[] password) {
        return new Pkcs12KeyMaterial(TlsMaterialUtils.readAllBytes(pkcs12File,
                "PKCS#12 certificate file could not be read"), password);
    }

    @Override
    public KeyManager[] keyManagers() {
        try {
            final KeyStore keyStore = loadKeyStore();
            requireSinglePrivateKeyEntry(keyStore);
            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password());
            return kmf.getKeyManagers();
        } catch (final GeneralSecurityException | IOException e) {
            throw new TlsMaterialException("PKCS#12 certificate file could not be loaded", e);
        }
    }

    private static void requireSinglePrivateKeyEntry(final KeyStore keyStore) throws GeneralSecurityException {
        int privateKeyEntries = 0;
        final Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                privateKeyEntries++;
            }
        }
        if (privateKeyEntries == 0) {
            throw new TlsMaterialException("PKCS#12 file contains no private-key entry");
        }
        if (privateKeyEntries > 1) {
            throw new TlsMaterialException(
                    "PKCS#12 file contains multiple private-key entries; a single entry is required");
        }
    }

}
