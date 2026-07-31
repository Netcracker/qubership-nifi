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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogFingerprintTest {

    @TempDir
    private Path root;

    private void write(final String relative, final String content) throws IOException {
        final Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isStableAcrossLineEndingsAndInputOrder() throws IOException {
        write("components/index.json", "[]\n");
        write("components/a/component.md", "# A\nbody\n");
        final String lf = CatalogFingerprint.compute(root,
                List.of("components/a/component.md", "components/index.json"));

        Files.write(root.resolve("components/a/component.md"), "# A\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));
        final String crlf = CatalogFingerprint.compute(root,
                List.of("components/index.json", "components/a/component.md"));

        assertThat(crlf).isEqualTo(lf);
        assertThat(lf).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void changesWhenCoveredContentChanges() throws IOException {
        write("components/index.json", "[]\n");
        final String before = CatalogFingerprint.compute(root, List.of("components/index.json"));
        write("components/index.json", "[1]\n");
        final String after = CatalogFingerprint.compute(root, List.of("components/index.json"));
        assertThat(after).isNotEqualTo(before);
    }
}
