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
package org.qubership.nifi;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Builds a mTLS-capable {@link HttpClient} from a PKCS12 client keystore and a PEM CA certificate.
 */
public final class NifiMtlsClient {

    private NifiMtlsClient() {
    }

    public static HttpClient build(final String certPath, final String certPassword,
                            final String caCertPath) throws Exception {
        KeyManagerFactory kmf = buildKeyManagerFactory(certPath, certPassword);
        TrustManagerFactory tmf = buildTrustManagerFactory(caCertPath);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }

    private static KeyManagerFactory buildKeyManagerFactory(final String certPath,
                                                            final String certPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(certPath)) {
            keyStore.load(is, certPassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, certPassword.toCharArray());
        return kmf;
    }

    private static TrustManagerFactory buildTrustManagerFactory(final String caCertPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream is = new FileInputStream(caCertPath)) {
            Collection<? extends Certificate> certs = cf.generateCertificates(is);
            int idx = 0;
            for (Certificate cert : certs) {
                trustStore.setCertificateEntry("ca-" + idx++, cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }
}
