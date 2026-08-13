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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fails when staged output replacement can discard an existing destination or leave swap data.
 *
 * <p>Staging must be created beside the destination, replacement must remove a successful backup,
 * and a failed staging move must restore the previous output. Recursive cleanup must remove whole
 * trees and accept a missing root. Preserve these recovery guarantees when changing the swap
 * sequence.</p>
 */
class OutputReplacerTest {

    private static final String OUTPUT_DIRECTORY = "output";
    private static final String NEW_FILE = "new.txt";
    private static final String OLD_FILE = "old.txt";

    @TempDir
    private Path temp;

    private final OutputReplacer replacer = new OutputReplacer();

    @Test
    void createsStagingDirectoryBesideDestination() {
        final Path staging = replacer.createStaging(temp.resolve(OUTPUT_DIRECTORY));

        assertThat(staging).isDirectory();
        assertThat(staging.getParent()).isEqualTo(temp);
        assertThat(staging.getFileName().toString()).startsWith(".kb-staging-");
    }

    @Test
    void reportsStagingCreationFailure() throws Exception {
        final Path parentFile = Files.writeString(temp.resolve("parent-file"), "content");

        assertThatThrownBy(() -> replacer.createStaging(parentFile.resolve(OUTPUT_DIRECTORY)))
                .isInstanceOf(OutputException.class)
                .hasMessageContaining("Failed to create staging directory");
    }

    @Test
    void movesStagingIntoMissingDestination() throws Exception {
        final Path destination = temp.resolve(OUTPUT_DIRECTORY);
        final Path staging = createStaging();

        replacer.replace(destination, staging);

        assertThat(destination.resolve(NEW_FILE)).hasContent("new");
        assertThat(staging).doesNotExist();
    }

    @Test
    void replacesExistingDestinationAndRemovesBackup() throws Exception {
        final Path destination = createDestination();
        final Path staging = createStaging();

        replacer.replace(destination, staging);

        assertThat(destination.resolve(NEW_FILE)).hasContent("new");
        assertThat(destination.resolve(OLD_FILE)).doesNotExist();
        try (var children = Files.list(temp)) {
            assertThat(children.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".kb-backup-"));
        }
    }

    @Test
    void restoresExistingDestinationWhenStagingMoveFails() throws Exception {
        final Path destination = createDestination();

        assertThatThrownBy(() -> replacer.replace(destination, temp.resolve("missing-staging")))
                .isInstanceOf(OutputException.class)
                .hasMessageContaining("Failed to replace the output directory");
        assertThat(destination.resolve(OLD_FILE)).hasContent("old");
    }

    @Test
    void deletesTreesAndIgnoresMissingRoots() throws Exception {
        final Path root = Files.createDirectories(temp.resolve("tree/nested"));
        Files.writeString(root.resolve("content.txt"), "content");

        replacer.deleteRecursively(temp.resolve("tree"));
        replacer.deleteRecursively(temp.resolve("missing"));

        assertThat(temp.resolve("tree")).doesNotExist();
    }

    private Path createDestination() throws IOException {
        final Path destination = Files.createDirectory(temp.resolve(OUTPUT_DIRECTORY));
        Files.writeString(destination.resolve(OLD_FILE), "old");
        return destination;
    }

    private Path createStaging() throws IOException {
        final Path staging = Files.createDirectory(temp.resolve("staging"));
        Files.writeString(staging.resolve(NEW_FILE), "new");
        return staging;
    }
}
