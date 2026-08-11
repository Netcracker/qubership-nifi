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

import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentRecord;

import java.util.Comparator;

/**
 * Deterministic ordering for components: by kind, simple type name, bundle group, artifact, version,
 * and fully qualified type.
 */
public final class ComponentSorting {

    /** The canonical component comparator. */
    public static final Comparator<ComponentRecord> BY_IDENTITY =
            Comparator.comparingInt((ComponentRecord record) -> record.identity().getKind().ordinal())
                    .thenComparing(record -> record.identity().simpleName())
                    .thenComparing(record -> record.identity().getGroup())
                    .thenComparing(record -> record.identity().getArtifact())
                    .thenComparing(record -> record.identity().getVersion())
                    .thenComparing(record -> record.identity().getType());

    private ComponentSorting() {
        // utility class
    }

    /**
     * Returns the relative path (from the {@code components/} directory) to a component's directory.
     *
     * @param identity the component identity
     * @return the relative directory path
     */
    public static String directoryPath(final ComponentIdentity identity) {
        return org.qubership.nifi.tools.kb.model.ComponentKindLayout.directoryName(identity.getKind())
                + "/" + identity.directoryName();
    }

    /**
     * Returns the relative path (from the {@code components/} directory) to a component's file.
     *
     * @param identity the component identity
     * @param fileName the file name within the component directory
     * @return the relative path
     */
    public static String relativePath(final ComponentIdentity identity, final String fileName) {
        return directoryPath(identity) + "/" + fileName;
    }
}
