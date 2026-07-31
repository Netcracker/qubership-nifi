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

package org.qubership.nifi.tools.kb.output;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans staged output files for secret byte sequences as defense in depth. To avoid spurious
 * matches, secrets shorter than {@link #MIN_SECRET_LENGTH} bytes are not scanned.
 */
public final class SecretScanner {

    /** The minimum secret length considered for scanning. */
    public static final int MIN_SECRET_LENGTH = 8;

    private SecretScanner() {
        // utility class
    }

    /**
     * Scans all regular files under the given root for any of the supplied secrets.
     *
     * @param root    the staging root
     * @param secrets the secret byte sequences
     * @throws OutputException when a secret is found in any file
     */
    public static void scan(final Path root, final List<byte[]> secrets) {
        final List<byte[]> scannable = secrets.stream().filter(s -> s.length >= MIN_SECRET_LENGTH).toList();
        if (scannable.isEmpty()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(file -> scanFile(file, scannable));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to scan staged output for secrets", e);
        }
    }

    private static void scanFile(final Path file, final List<byte[]> secrets) {
        final byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read staged file for secret scan: " + file, e);
        }
        for (final byte[] secret : secrets) {
            if (contains(content, secret)) {
                throw new OutputException("A secret value was found in generated output: " + file.getFileName());
            }
        }
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (matchesAt(haystack, needle, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(final byte[] haystack, final byte[] needle, final int offset) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }
}
