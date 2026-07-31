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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Turns a build failure into a diagnostic and an exit code. The message follows the
 * {@code reason + action + consequence} pattern; stack traces are emitted only at debug level.
 */
public final class ExitCodeExceptionHandler implements CommandLine.IExecutionExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExitCodeExceptionHandler.class);

    /**
     * Reports the failure and returns its exit code.
     *
     * @param ex          the failure raised by the command
     * @param commandLine the command line that was executing
     * @param parseResult the parse result, unused
     * @return the exit code for the failure category
     */
    @Override
    public int handleExecutionException(final Exception ex, final CommandLine commandLine,
                                        final CommandLine.ParseResult parseResult) {
        commandLine.getErr().println(ex.getMessage() + " The previous output, if any, was left unchanged.");
        LOG.debug("Build failed", ex);
        return FailureClassifier.classify(ex);
    }
}
