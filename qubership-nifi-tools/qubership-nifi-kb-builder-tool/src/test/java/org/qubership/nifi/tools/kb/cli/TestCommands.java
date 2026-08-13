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

import picocli.CommandLine;

/**
 * Builds commands for the tests that exercise argument parsing only. The collaborators are left null
 * deliberately: {@link BuildCommand#call()} is never reached from here, and a stub builder would
 * suggest that it might be.
 */
final class TestCommands {

    private TestCommands() {
        // test helper
    }

    /**
     * Creates a command that can be parsed into but not run.
     *
     * @return the command
     */
    static BuildCommand newCommand() {
        return new BuildCommand(null, null);
    }

    /**
     * Parses arguments into a fresh command.
     *
     * @param args the command-line arguments
     * @return the populated command
     */
    static BuildCommand populate(final String... args) {
        final BuildCommand command = newCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }
}
