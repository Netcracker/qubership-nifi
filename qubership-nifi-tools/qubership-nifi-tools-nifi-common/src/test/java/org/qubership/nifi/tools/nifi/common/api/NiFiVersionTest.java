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

package org.qubership.nifi.tools.nifi.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NiFiVersionTest {

    private static final NiFiVersion LOWER = NiFiVersion.of(2, 5, 0);
    private static final NiFiVersion UPPER = NiFiVersion.of(3, 0, 0);

    @Test
    void parsesNumericTupleWithSuffix() {
        final NiFiVersion version = NiFiVersion.parse("2.5.0-SNAPSHOT").orElseThrow();
        assertThat(version.getMajor()).isEqualTo(2);
        assertThat(version.getMinor()).isEqualTo(5);
        assertThat(version.getPatch()).isZero();
        assertThat(version.getRaw()).isEqualTo("2.5.0-SNAPSHOT");
    }

    @Test
    void rejectsMalformedVersion() {
        assertThat(NiFiVersion.parse("not-a-version")).isEmpty();
        assertThat(NiFiVersion.parse("2.5")).isEmpty();
        assertThat(NiFiVersion.parse("999999999999999999999.5.0")).isEmpty();
        assertThat(NiFiVersion.parse(null)).isEmpty();
    }

    @Test
    void acceptsOnlyTheDocumentedQualifierShape() {
        assertThat(NiFiVersion.parse("  2.5.0")).isPresent();
        assertThat(NiFiVersion.parse("2.5.0-RC1")).isPresent();
        assertThat(NiFiVersion.parse("2.5.0 ")).isEmpty();
        assertThat(NiFiVersion.parse("2.7.2.1")).isEmpty();
        assertThat(NiFiVersion.parse("2.6.0-RC.1")).isEmpty();
        assertThat(NiFiVersion.parse("2.5.0-SNAPSHOT-1")).isEmpty();
    }

    @Test
    void comparesNumericallyNotLexicographically() {
        assertThat(NiFiVersion.of(2, 10, 0)).isGreaterThan(NiFiVersion.of(2, 9, 0));
    }

    @Test
    void enforcesSupportedInterval() {
        assertThat(NiFiVersion.of(2, 4, 99).isWithin(LOWER, UPPER)).isFalse();
        assertThat(NiFiVersion.of(2, 5, 0).isWithin(LOWER, UPPER)).isTrue();
        assertThat(NiFiVersion.of(2, 10, 0).isWithin(LOWER, UPPER)).isTrue();
        assertThat(NiFiVersion.of(3, 0, 0).isWithin(LOWER, UPPER)).isFalse();
    }

    @Test
    void onlyMajorVersionOneIsTheNiFi1xFamily() {
        assertThat(NiFiVersion.of(0, 9, 9).isNiFi1x()).as("0.9.9").isFalse();
        assertThat(NiFiVersion.of(1, 0, 0).isNiFi1x()).as("1.0.0").isTrue();
        assertThat(NiFiVersion.of(1, 28, 1).isNiFi1x()).as("1.28.1").isTrue();
        assertThat(NiFiVersion.of(2, 0, 0).isNiFi1x()).as("2.0.0").isFalse();
    }

    @Test
    void aRawVersionIsNiFi1xOnlyWhenItParses() {
        assertThat(NiFiVersion.isNiFi1x("1.26.0-SNAPSHOT")).as("1.26.0-SNAPSHOT").isTrue();
        assertThat(NiFiVersion.isNiFi1x("2.5.0")).as("2.5.0").isFalse();
        assertThat(NiFiVersion.isNiFi1x("1.x")).as("1.x").isFalse();
        assertThat(NiFiVersion.isNiFi1x(null)).as("null").isFalse();
    }

    @Test
    void comparesEqualityByNumericTuple() {
        final NiFiVersion version = NiFiVersion.of(2, 5, 1);
        final NiFiVersion equalWithSuffix = NiFiVersion.parse("2.5.1-SNAPSHOT").orElseThrow();

        assertThat(version).isEqualTo(version).isEqualTo(equalWithSuffix);
        assertThat(version.hashCode()).isEqualTo(equalWithSuffix.hashCode());
        assertThat(version.toString()).isEqualTo("2.5.1");
        assertThat(version)
                .isNotEqualTo(null)
                .isNotEqualTo("2.5.1")
                .isNotEqualTo(NiFiVersion.of(3, 5, 1))
                .isNotEqualTo(NiFiVersion.of(2, 6, 1))
                .isNotEqualTo(NiFiVersion.of(2, 5, 2));
    }
}
