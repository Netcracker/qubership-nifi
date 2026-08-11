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

import org.qubership.nifi.tools.kb.collect.UnsupportedTargetException;
import org.qubership.nifi.tools.kb.output.OutputException;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.tls.TlsMaterialException;

import javax.net.ssl.SSLException;

/**
 * Maps a build failure to the process exit code that describes its category. Kept free of picocli
 * types so that the mapping can be unit-tested on its own.
 */
public final class FailureClassifier {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    private FailureClassifier() {
        // utility class
    }

    /**
     * Classifies a failure into an exit code.
     *
     * @param failure the failure to classify
     * @return the exit code
     */
    public static int classify(final Throwable failure) {
        if (failure instanceof ConfigurationException || failure instanceof IllegalArgumentException) {
            return ExitCodes.USAGE;
        }
        if (failure instanceof UnsupportedTargetException) {
            return ExitCodes.UNSUPPORTED_TARGET;
        }
        if (failure instanceof OutputException) {
            return ExitCodes.OUTPUT;
        }
        if (failure instanceof NiFiApiException apiException) {
            return classifyApi(apiException);
        }
        if (failure instanceof TlsMaterialException || hasCause(failure, SSLException.class)) {
            return ExitCodes.AUTH;
        }
        return ExitCodes.COLLECTION;
    }

    private static int classifyApi(final NiFiApiException apiException) {
        if (apiException.getStatusCode() == HTTP_UNAUTHORIZED || hasCause(apiException, SSLException.class)) {
            return ExitCodes.AUTH;
        }
        if (apiException.getStatusCode() == HTTP_FORBIDDEN) {
            return ExitCodes.AUTHORIZATION;
        }
        return ExitCodes.COLLECTION;
    }

    private static boolean hasCause(final Throwable throwable, final Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
