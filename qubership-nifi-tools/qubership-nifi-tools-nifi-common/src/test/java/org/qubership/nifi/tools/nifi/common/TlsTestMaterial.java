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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Generates TLS material for tests instead of shipping key stores as binary test resources.
 *
 * <p>Every certificate is self-signed with a fresh RSA key pair and is valid for a day, so the
 * material cannot expire in the repository and cannot be mistaken for a credential. The DER for the
 * certificate is assembled here because the JDK exposes no public certificate builder.
 */
final class TlsTestMaterial {

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final int SERIAL_BITS = 64;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String PKCS12 = "PKCS12";
    private static final String X509 = "X.509";

    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_BIT_STRING = 0x03;
    private static final int TAG_UTF8_STRING = 0x0C;
    private static final int TAG_UTC_TIME = 0x17;
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_SET = 0x31;
    private static final int TAG_CONTEXT_CONSTRUCTED = 0xA0;

    private static final int TBS_VERSION_V3 = 2;
    private static final int TBS_EXTENSIONS_INDEX = 3;

    /** AlgorithmIdentifier for sha256WithRSAEncryption, OID 1.2.840.113549.1.1.11, absent parameters. */
    private static final byte[] SHA256_WITH_RSA = {
        0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D,
        0x01, 0x01, 0x0B, 0x05, 0x00};

    /** AttributeType commonName, OID 2.5.4.3. */
    private static final byte[] COMMON_NAME_OID = {0x06, 0x03, 0x55, 0x04, 0x03};

    /** Critical basicConstraints extension, OID 2.5.29.19, with CA set to true. */
    private static final byte[] BASIC_CONSTRAINTS_CA = {
        0x30, 0x0F, 0x06, 0x03, 0x55, 0x1D, 0x13, 0x01, 0x01, (byte) 0xFF,
        0x04, 0x05, 0x30, 0x03, 0x01, 0x01, (byte) 0xFF};

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'");
    private static final int PEM_LINE_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TlsTestMaterial() {
    }

    /**
     * Writes a PKCS#12 file holding one self-signed key entry per alias. Entry passwords match the
     * store password, so the file loads the way a client certificate exported from a browser does.
     *
     * @param file     the file to write
     * @param password the store and entry password
     * @param aliases  the key entry aliases; each alias also becomes the certificate common name
     * @return the written file
     * @throws Exception when the key store cannot be built or written
     */
    static Path writePkcs12(final Path file, final char[] password, final String... aliases) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(PKCS12);
        keyStore.load(null, password);
        for (final String alias : aliases) {
            final KeyPair keyPair = generateKeyPair();
            final X509Certificate certificate = selfSignedCertificate(keyPair, alias);
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[]{certificate});
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, password);
        }
        return file;
    }

    /**
     * Writes a PEM file holding a single self-signed certificate.
     *
     * @param file       the file to write
     * @param commonName the certificate common name
     * @return the written file
     * @throws Exception when the certificate cannot be built or written
     */
    static Path writeCertificatePem(final Path file, final String commonName) throws Exception {
        final X509Certificate certificate = selfSignedCertificate(generateKeyPair(), commonName);
        final Base64.Encoder encoder =
                Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".getBytes(StandardCharsets.US_ASCII));
        final String pem = "-----BEGIN CERTIFICATE-----\n"
                + encoder.encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        return file;
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(KEY_SIZE);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSignedCertificate(final KeyPair keyPair, final String commonName)
            throws Exception {
        final byte[] tbsCertificate = tbsCertificate(keyPair, commonName);
        final Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCertificate);
        final byte[] der = derSequence(
                tbsCertificate,
                SHA256_WITH_RSA,
                derValue(TAG_BIT_STRING, concat(new byte[]{0}, signer.sign())));
        final CertificateFactory factory = CertificateFactory.getInstance(X509);
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] tbsCertificate(final KeyPair keyPair, final String commonName) {
        final byte[] name = distinguishedName(commonName);
        final ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return derSequence(
                derExplicit(0, derValue(TAG_INTEGER, new byte[]{TBS_VERSION_V3})),
                derValue(TAG_INTEGER, serialNumber().toByteArray()),
                SHA256_WITH_RSA,
                name,
                derSequence(utcTime(now.minusHours(1)), utcTime(now.plusDays(1))),
                name,
                keyPair.getPublic().getEncoded(),
                derExplicit(TBS_EXTENSIONS_INDEX, derSequence(BASIC_CONSTRAINTS_CA)));
    }

    private static BigInteger serialNumber() {
        return new BigInteger(SERIAL_BITS, RANDOM).add(BigInteger.ONE);
    }

    private static byte[] distinguishedName(final String commonName) {
        final byte[] value = derValue(TAG_UTF8_STRING, commonName.getBytes(StandardCharsets.UTF_8));
        return derSequence(derValue(TAG_SET, derSequence(COMMON_NAME_OID, value)));
    }

    private static byte[] utcTime(final ZonedDateTime moment) {
        return derValue(TAG_UTC_TIME, UTC_TIME.format(moment).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derSequence(final byte[]... elements) {
        return derValue(TAG_SEQUENCE, concat(elements));
    }

    private static byte[] derExplicit(final int index, final byte[] content) {
        return derValue(TAG_CONTEXT_CONSTRUCTED | index, content);
    }

    private static byte[] derValue(final int tag, final byte[] content) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.writeBytes(derLength(content.length));
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] derLength(final int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        int byteCount = 1;
        while (length >>> (Byte.SIZE * byteCount) != 0) {
            byteCount++;
        }
        final byte[] encoded = new byte[byteCount + 1];
        encoded[0] = (byte) (0x80 | byteCount);
        for (int i = 0; i < byteCount; i++) {
            encoded[i + 1] = (byte) (length >>> (Byte.SIZE * (byteCount - 1 - i)));
        }
        return encoded;
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
