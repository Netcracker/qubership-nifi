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

import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.kb.collect.CollectionException;
import org.qubership.nifi.tools.kb.collect.UnsupportedTargetException;
import org.qubership.nifi.tools.kb.docs.GuideException;
import org.qubership.nifi.tools.kb.output.OutputException;
import org.qubership.nifi.tools.nifi.common.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.TlsMaterialException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void mapsConfigurationFailuresToUsage() {
        assertThat(FailureClassifier.classify(new ConfigurationException("bad")))
                .isEqualTo(ExitCodes.USAGE);
        assertThat(FailureClassifier.classify(new IllegalArgumentException("bad")))
                .isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void mapsUnsupportedTargetToItsOwnCode() {
        assertThat(FailureClassifier.classify(new UnsupportedTargetException("too old")))
                .isEqualTo(ExitCodes.UNSUPPORTED_TARGET);
    }

    @Test
    void mapsOutputFailuresToOutputCode() {
        assertThat(FailureClassifier.classify(new OutputException("cannot write")))
                .isEqualTo(ExitCodes.OUTPUT);
    }

    @Test
    void mapsUnauthorizedAndForbiddenSeparately() {
        assertThat(FailureClassifier.classify(apiFailure(401))).isEqualTo(ExitCodes.AUTH);
        assertThat(FailureClassifier.classify(apiFailure(403))).isEqualTo(ExitCodes.AUTHORIZATION);
        assertThat(FailureClassifier.classify(apiFailure(500))).isEqualTo(ExitCodes.COLLECTION);
    }

    private static NiFiApiException apiFailure(final int status) {
        return new NiFiApiException("GET", "https://nifi.example.com/nifi-api/flow/about", status,
                "", "request failed");
    }

    @Test
    void mapsTlsFailuresToAuth() {
        assertThat(FailureClassifier.classify(new TlsMaterialException("bad keystore")))
                .isEqualTo(ExitCodes.AUTH);
        assertThat(FailureClassifier.classify(new IllegalStateException(
                new IOException(new SSLHandshakeException("untrusted")))))
                .isEqualTo(ExitCodes.AUTH);
    }

    @Test
    void mapsCollectionAndGuideFailuresToCollection() {
        assertThat(FailureClassifier.classify(new CollectionException("incomplete")))
                .isEqualTo(ExitCodes.COLLECTION);
        assertThat(FailureClassifier.classify(new GuideException("missing heading")))
                .isEqualTo(ExitCodes.COLLECTION);
        assertThat(FailureClassifier.classify(new RuntimeException("unexpected")))
                .isEqualTo(ExitCodes.COLLECTION);
    }
}
