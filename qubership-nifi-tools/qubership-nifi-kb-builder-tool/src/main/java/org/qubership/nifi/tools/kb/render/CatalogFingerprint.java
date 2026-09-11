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

package org.qubership.nifi.tools.kb.render;

import org.qubership.nifi.tools.kb.DigestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Computes the aggregate Knowledge Base fingerprint: a lowercase SHA-256 digest, prefixed with
 * {@code sha256:}, over the normalized (LF) content of the covered files, which are the component
 * output and, in a full build, the guides. Paths are sorted by their UTF-8 byte sequence and each
 * path/content pair is framed with unambiguous eight-byte big-endian length prefixes so path and
 * content boundaries cannot collide. Content that never gets a file of its own can be digested
 * alongside them as a labeled virtual entry.
 *
 * <p>Line endings are normalized to LF only in the digest input; the on-disk bytes of verbatim
 * files are never rewritten.</p>
 */
public final class CatalogFingerprint {

    private static final String PREFIX = "sha256:";
    private static final int LENGTH_FIELD_BYTES = 8;
    private static final int BYTE_MASK = 0xFF;

    private CatalogFingerprint() {
        // utility class
    }

    /**
     * Computes the fingerprint over the given covered relative paths under {@code root}.
     *
     * @param root          the staging root directory
     * @param relativePaths the covered relative paths (using {@code /} separators)
     * @return the {@code sha256:}-prefixed fingerprint
     * @throws UncheckedIOException  when a covered file cannot be read
     * @throws IllegalStateException when SHA-256 is unavailable in this JVM
     */
    public static String compute(final Path root, final List<String> relativePaths) {
        return compute(root, relativePaths, Map.of());
    }

    /**
     * Computes the fingerprint over the given covered relative paths under {@code root}, together
     * with content that is not covered by a file of its own.
     *
     * <p>The manifest carries the fingerprint, so it is written after the digest exists and cannot
     * cover itself. Content that lives only in the manifest is passed here instead, under a label
     * that names where it ends up, so it stays inside the fingerprint.
     *
     * @param root          the staging root directory
     * @param relativePaths the covered relative paths (using {@code /} separators)
     * @param virtual       labeled content to digest alongside the files
     * @return the {@code sha256:}-prefixed fingerprint
     * @throws UncheckedIOException  when a covered file cannot be read
     * @throws IllegalStateException when SHA-256 is unavailable in this JVM
     */
    public static String compute(final Path root, final List<String> relativePaths,
                                 final Map<String, byte[]> virtual) {
        final List<String> labels = new ArrayList<>(relativePaths);
        labels.addAll(virtual.keySet());
        labels.sort(Comparator.comparing(label -> label.getBytes(StandardCharsets.UTF_8),
                CatalogFingerprint::compareUnsigned));
        final MessageDigest digest = DigestUtils.newSha256Digest();
        for (final String label : labels) {
            final byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
            final byte[] source = virtual.containsKey(label) ? virtual.get(label) : read(root.resolve(label));
            final byte[] contentBytes = normalizeToLf(source);
            digest.update(lengthPrefix(labelBytes.length));
            digest.update(labelBytes);
            digest.update(lengthPrefix(contentBytes.length));
            digest.update(contentBytes);
        }
        return PREFIX + DigestUtils.toLowerHex(digest.digest());
    }

    private static byte[] read(final Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read catalog file for fingerprinting: " + file, e);
        }
    }

    private static byte[] normalizeToLf(final byte[] raw) {
        final String text = new String(raw, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] lengthPrefix(final long length) {
        return ByteBuffer.allocate(LENGTH_FIELD_BYTES).putLong(length).array();
    }

    private static int compareUnsigned(final byte[] left, final byte[] right) {
        final int min = Math.min(left.length, right.length);
        for (int i = 0; i < min; i++) {
            final int diff = (left[i] & BYTE_MASK) - (right[i] & BYTE_MASK);
            if (diff != 0) {
                return diff;
            }
        }
        return left.length - right.length;
    }

}
