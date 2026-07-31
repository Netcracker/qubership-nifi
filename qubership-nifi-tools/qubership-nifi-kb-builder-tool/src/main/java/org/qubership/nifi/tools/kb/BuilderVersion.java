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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The builder's own name and version, recorded in the Knowledge Base provenance and printed by
 * {@code --version}. Both values come from {@code kb-builder.properties}, a resource Maven filters at
 * build time with the artifact id and project version.
 */
public final class BuilderVersion {

    private static final String RESOURCE = "/kb-builder.properties";

    private final String name;
    private final String version;

    /**
     * Creates the version holder.
     *
     * @param builderName    the builder name
     * @param builderVersion the builder version
     */
    public BuilderVersion(final String builderName, final String builderVersion) {
        this.name = builderName;
        this.version = builderVersion;
    }

    /**
     * Reads the builder identity from the packaged resource.
     *
     * @return the builder identity
     * @throws IllegalStateException when the resource is missing or incomplete, which means the jar was
     *                               assembled without the filtered resource
     */
    public static BuilderVersion load() {
        final Properties properties = new Properties();
        try (InputStream in = BuilderVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Resource " + RESOURCE + " is missing from the classpath");
            }
            properties.load(in);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read " + RESOURCE, e);
        }
        return new BuilderVersion(require(properties, "name"), require(properties, "version"));
    }

    private static String require(final Properties properties, final String key) {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Property '" + key + "' is missing from " + RESOURCE);
        }
        return value;
    }

    /**
     * Returns the builder name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the builder version.
     *
     * @return the version
     */
    public String version() {
        return version;
    }
}
