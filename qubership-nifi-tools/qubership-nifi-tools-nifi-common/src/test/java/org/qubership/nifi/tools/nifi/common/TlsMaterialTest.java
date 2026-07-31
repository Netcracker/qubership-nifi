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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsMaterialTest {

    private static final char[] CORRECT_PASSWORD = "changeit".toCharArray();

    private static Path clientKeyStore;
    private static Path twoKeyKeyStore;
    private static Path caCertificate;

    @BeforeAll
    static void generateTlsMaterial(@TempDir final Path tlsDir) throws Exception {
        clientKeyStore = TlsTestMaterial.writePkcs12(tlsDir.resolve("client.p12"), CORRECT_PASSWORD, "client");
        twoKeyKeyStore = TlsTestMaterial.writePkcs12(
                tlsDir.resolve("two-keys.p12"), CORRECT_PASSWORD, "first-client", "second-client");
        caCertificate = TlsTestMaterial.writeCertificatePem(tlsDir.resolve("ca.pem"), "qubership-nifi-test-ca");
    }

    @Test
    void loadsSinglePrivateKeyMaterial() {
        final Pkcs12KeyMaterial material =
                Pkcs12KeyMaterial.fromFile(clientKeyStore, CORRECT_PASSWORD);
        assertThat(material.keyManagers()).isNotEmpty();
    }

    @Test
    void rejectsWrongPassword() {
        final Pkcs12KeyMaterial material =
                Pkcs12KeyMaterial.fromFile(clientKeyStore, "wrong".toCharArray());
        assertThatThrownBy(material::keyManagers).isInstanceOf(TlsMaterialException.class);
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> Pkcs12KeyMaterial.fromFile(Paths.get("does-not-exist.p12"), CORRECT_PASSWORD))
                .isInstanceOf(TlsMaterialException.class);
    }

    @Test
    void rejectsAmbiguousMultipleKeyEntries() {
        final Pkcs12KeyMaterial material =
                Pkcs12KeyMaterial.fromFile(twoKeyKeyStore, CORRECT_PASSWORD);
        assertThatThrownBy(material::keyManagers)
                .isInstanceOf(TlsMaterialException.class)
                .hasMessageContaining("multiple private-key entries");
    }

    @Test
    void loadsPemTrustMaterial() {
        final PemTrustMaterial material = PemTrustMaterial.fromFile(caCertificate);
        assertThat(material.trustManagers()).isNotEmpty();
    }

    @Test
    void rejectsMalformedPem() {
        final PemTrustMaterial material = PemTrustMaterial.fromBytes("not a certificate".getBytes());
        assertThatThrownBy(material::trustManagers).isInstanceOf(TlsMaterialException.class);
    }

    @Test
    void buildsContextWithJvmDefaultTrustWhenTrustAbsent() {
        assertThat(TlsContextFactory.create(Optional.empty(), Optional.empty())).isNotNull();
    }

    @Test
    void buildsContextWithCustomTrust() {
        final TrustMaterial trust = PemTrustMaterial.fromFile(caCertificate);
        assertThat(TlsContextFactory.create(Optional.empty(), Optional.of(trust))).isNotNull();
    }
}
