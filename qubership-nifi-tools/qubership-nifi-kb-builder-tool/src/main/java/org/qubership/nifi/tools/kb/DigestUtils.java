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

package org.qubership.nifi.tools.kb;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Creates SHA-256 digests and renders digest bytes as lowercase hexadecimal text.
 */
public final class DigestUtils {

    private static final String SHA_256 = "SHA-256";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int BYTE_MASK = 0xFF;
    private static final int NIBBLE_MASK = 0x0F;
    private static final int HIGH_NIBBLE_SHIFT = 4;

    private DigestUtils() {
        // utility class
    }

    /**
     * Creates a new SHA-256 digest.
     *
     * @return the digest
     * @throws IllegalStateException when SHA-256 is unavailable in the JVM
     */
    public static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(SHA_256 + " is not available", e);
        }
    }

    /**
     * Returns the SHA-256 digest of the supplied bytes.
     *
     * @param input the bytes to digest
     * @return the 32-byte digest
     */
    public static byte[] sha256(final byte[] input) {
        return newSha256Digest().digest(input);
    }

    /**
     * Returns lowercase hexadecimal text for the supplied bytes.
     *
     * @param bytes the bytes to encode
     * @return two hexadecimal characters per input byte
     */
    public static String toLowerHex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (final byte current : bytes) {
            final int value = current & BYTE_MASK;
            hex.append(HEX_DIGITS[(value >> HIGH_NIBBLE_SHIFT) & NIBBLE_MASK]);
            hex.append(HEX_DIGITS[value & NIBBLE_MASK]);
        }
        return hex.toString();
    }
}
