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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fails when output scanning misses an exact secret or reports an ineligible value.
 *
 * <p>The scanner must ignore secrets below its minimum length, inspect regular files recursively,
 * reject an exact scannable byte sequence at any offset, and surface traversal failures. Preserve
 * exact byte matching and the minimum-length rule when changing the scanning algorithm.</p>
 */
class SecretScannerTest {

    @TempDir
    private Path temp;

    @Test
    void ignoresEmptyAndShortSecrets() throws Exception {
        Files.writeString(temp.resolve("content.txt"), "contains short");

        assertThatCode(() -> SecretScanner.scan(temp, List.of(
                new byte[0], "short".getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }

    @Test
    void scansFilesRecursivelyWithoutMatchingPrefixesOrLongerSecrets() throws Exception {
        final Path nested = Files.createDirectories(temp.resolve("nested"));
        Files.writeString(nested.resolve("content.txt"), "prefix-secretX-value-suffix");

        assertThatCode(() -> SecretScanner.scan(temp, List.of(
                "secretY-value".getBytes(StandardCharsets.UTF_8),
                "this-secret-is-longer-than-the-file-content".getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSecretAtNonzeroOffset() throws Exception {
        Files.writeString(temp.resolve("content.txt"), "prefix-secret-value-suffix");

        assertThatThrownBy(() -> SecretScanner.scan(temp,
                List.of("secret-value".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(OutputException.class)
                .hasMessageContaining("content.txt");
    }

    @Test
    void reportsMissingScanRoot() {
        assertThatThrownBy(() -> SecretScanner.scan(temp.resolve("missing"),
                List.of("secret-value".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to scan staged output");
    }
}
