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

package org.qubership.nifi.tools.kb.model;

import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when component identities stop producing stable names, hashes, or equality results.
 *
 * <p>The complete identity tuple must determine canonical text, equality, and hash codes. Output
 * directory names must combine a sanitized simple type name with the fixed SHA-256 prefix. Treat
 * an intentional change to either representation as an output-contract migration, not only an
 * expected-value update.</p>
 */
class ComponentIdentityTest {

    @Test
    void exposesCanonicalIdentityAndReadableNames() {
        final ComponentIdentity identity = identity(
                NiFiComponentKind.PROCESSOR, "group", "artifact", "1.0", "Az09-_Invalid Name");

        assertThat(identity.simpleName()).isEqualTo("Az09-_Invalid Name");
        assertThat(identity.canonical())
                .isEqualTo("PROCESSOR\ngroup\nartifact\n1.0\nAz09-_Invalid Name");
        assertThat(identity.identityHash()).matches("[0-9a-f]{12}");
        assertThat(identity.directoryName()).matches("Az09-_Invalid_Name-[0-9a-f]{12}");
        assertThat(identity.toString())
                .isEqualTo("PROCESSOR group:artifact:1.0:Az09-_Invalid Name");
    }

    @Test
    void handlesUnqualifiedAndEmptyTypes() {
        assertThat(identity(NiFiComponentKind.PROCESSOR, "g", "a", "v", "Simple").simpleName())
                .isEqualTo("Simple");
        assertThat(identity(NiFiComponentKind.PROCESSOR, "g", "a", "v", "").directoryName())
                .matches("component-[0-9a-f]{12}");
    }

    @Test
    void comparesEveryIdentityField() {
        final ComponentIdentity identity = identity(NiFiComponentKind.PROCESSOR, "g", "a", "v", "Type");
        final ComponentIdentity equal = identity(NiFiComponentKind.PROCESSOR, "g", "a", "v", "Type");

        assertThat(identity).isEqualTo(equal);
        assertThat(identity.hashCode()).isEqualTo(equal.hashCode());
        assertThat(identity)
                .isNotEqualTo(null)
                .isNotEqualTo(identity(NiFiComponentKind.REPORTING_TASK, "g", "a", "v", "Type"))
                .isNotEqualTo(identity(NiFiComponentKind.PROCESSOR, "other", "a", "v", "Type"))
                .isNotEqualTo(identity(NiFiComponentKind.PROCESSOR, "g", "other", "v", "Type"))
                .isNotEqualTo(identity(NiFiComponentKind.PROCESSOR, "g", "a", "other", "Type"))
                .isNotEqualTo(identity(NiFiComponentKind.PROCESSOR, "g", "a", "v", "Other"));
    }

    private static ComponentIdentity identity(final NiFiComponentKind kind, final String group,
                                              final String artifact, final String version, final String type) {
        return new ComponentIdentity(kind, group, artifact, version, type);
    }
}
