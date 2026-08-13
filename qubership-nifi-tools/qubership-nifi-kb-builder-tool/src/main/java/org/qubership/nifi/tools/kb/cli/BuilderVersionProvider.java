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

package org.qubership.nifi.tools.kb.cli;

import org.qubership.nifi.tools.kb.BuilderVersion;
import picocli.CommandLine;

/**
 * Supplies the single line printed by {@code --version}.
 */
public final class BuilderVersionProvider implements CommandLine.IVersionProvider {

    private final BuilderVersion builderVersion;

    /**
     * Creates the version provider over the packaged builder identity. picocli instantiates the
     * version provider through its default factory when it builds the command model, so this class
     * needs a constructor that takes no arguments.
     */
    public BuilderVersionProvider() {
        this(BuilderVersion.load());
    }

    /**
     * Creates the version provider.
     *
     * @param version the builder version holder
     */
    public BuilderVersionProvider(final BuilderVersion version) {
        this.builderVersion = version;
    }

    /**
     * Returns the version banner lines.
     *
     * @return a single line holding the builder name and version
     */
    @Override
    public String[] getVersion() {
        return new String[]{builderVersion.name() + " " + builderVersion.version()};
    }
}
