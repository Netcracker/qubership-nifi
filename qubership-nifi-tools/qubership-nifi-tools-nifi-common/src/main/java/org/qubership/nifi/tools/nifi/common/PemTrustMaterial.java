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

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Trust material built from a PEM file containing one or more {@code CERTIFICATE} blocks. The
 * parsed certificates form an in-memory trust store that replaces the JVM default trust anchors for
 * the client it configures.
 */
public final class PemTrustMaterial implements TrustMaterial {

    private static final String X509 = "X.509";
    private static final String ENTRY_PREFIX = "ca-";

    private final byte[] pemBytes;

    private PemTrustMaterial(final byte[] bytes) {
        this.pemBytes = bytes.clone();
    }

    /**
     * Loads PEM trust material from a file.
     *
     * @param pemFile the path to a PEM file containing one or more CA certificates
     * @return the trust material
     * @throws TlsMaterialException when the file is missing, unreadable, or contains no certificate
     */
    public static PemTrustMaterial fromFile(final Path pemFile) {
        try {
            return new PemTrustMaterial(Files.readAllBytes(pemFile));
        } catch (final IOException e) {
            throw new TlsMaterialException("CA file could not be read", e);
        }
    }

    /**
     * Creates PEM trust material from raw bytes.
     *
     * @param bytes the PEM content
     * @return the trust material
     */
    public static PemTrustMaterial fromBytes(final byte[] bytes) {
        return new PemTrustMaterial(bytes);
    }

    @Override
    public TrustManager[] trustManagers() {
        try {
            final CertificateFactory factory = CertificateFactory.getInstance(X509);
            final Collection<? extends Certificate> certificates;
            try (InputStream in = new ByteArrayInputStream(pemBytes)) {
                certificates = factory.generateCertificates(in);
            }
            if (certificates.isEmpty()) {
                throw new TlsMaterialException("CA file contains no certificates");
            }
            final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (final Certificate certificate : certificates) {
                trustStore.setCertificateEntry(ENTRY_PREFIX + index, certificate);
                index++;
            }
            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        } catch (final GeneralSecurityException | IOException e) {
            throw new TlsMaterialException("CA file is not valid PEM certificate material", e);
        }
    }
}
