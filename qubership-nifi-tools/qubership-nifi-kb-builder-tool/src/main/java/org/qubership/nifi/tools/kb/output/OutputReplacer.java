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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Performs safe output replacement: the destination is never altered until a validated build exists
 * in a staging sibling on the same filesystem. Because Windows cannot atomically replace a populated
 * directory, the move-away-then-move-in order is load-bearing: the existing destination is moved to
 * a backup sibling first, staging is moved into place, and the backup is restored on a failed move.
 */
public final class OutputReplacer {

    private static final Logger LOG = LoggerFactory.getLogger(OutputReplacer.class);
    private static final String STAGING_PREFIX = ".kb-staging-";
    private static final String BACKUP_PREFIX = ".kb-backup-";

    /**
     * Creates a fresh staging directory beside the destination, on the same filesystem.
     *
     * @param destination the final destination
     * @return the staging directory
     * @throws OutputException when the staging directory cannot be created
     */
    public Path createStaging(final Path destination) {
        final Path staging = uniqueSibling(destination, STAGING_PREFIX);
        try {
            Files.createDirectories(staging);
            return staging;
        } catch (final IOException e) {
            throw new OutputException("Failed to create staging directory beside the destination", e);
        }
    }

    /**
     * Replaces the destination with the staging directory using a recoverable backup swap.
     *
     * @param destination the final destination
     * @param staging     the validated staging directory
     * @throws OutputException when replacement fails
     */
    public void replace(final Path destination, final Path staging) {
        Path backup = null;
        try {
            if (Files.exists(destination)) {
                backup = uniqueSibling(destination, BACKUP_PREFIX);
                Files.move(destination, backup);
            }
            moveIntoPlace(destination, staging, backup);
        } catch (final IOException e) {
            throw new OutputException("Failed to replace the output directory", e);
        }
        if (backup != null) {
            deleteRecursively(backup);
        }
    }

    private void moveIntoPlace(final Path destination, final Path staging, final Path backup) throws IOException {
        try {
            Files.move(staging, destination);
        } catch (final IOException e) {
            if (backup != null) {
                restoreBackup(destination, backup);
            }
            throw e;
        }
    }

    private void restoreBackup(final Path destination, final Path backup) {
        try {
            Files.move(backup, destination);
        } catch (final IOException restoreFailure) {
            LOG.error("Failed to restore the previous output from backup {}", backup, restoreFailure);
        }
    }

    /**
     * Deletes a directory tree without following symbolic links.
     *
     * @param root the directory to delete
     */
    public void deleteRecursively(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(OutputReplacer::deleteQuietly);
        } catch (final IOException e) {
            LOG.warn("Failed to fully delete {}", root, e);
        }
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException e) {
            LOG.warn("Failed to delete {}", path, e);
        }
    }

    private static Path uniqueSibling(final Path destination, final String prefix) {
        final String name = prefix + UUID.randomUUID();
        final Path parent = destination.getParent();
        return parent == null ? destination.resolveSibling(name) : parent.resolve(name);
    }
}
