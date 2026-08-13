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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Loads TLS material bytes and creates trust managers from populated key stores.
 */
final class TlsMaterialUtils {

    private TlsMaterialUtils() {
        // utility class
    }

    static byte[] readAllBytes(final Path file, final String failureMessage) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new TlsMaterialException(failureMessage, e);
        }
    }

    static TrustManager[] createTrustManagers(final KeyStore trustStore) throws GeneralSecurityException {
        final TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }
}
