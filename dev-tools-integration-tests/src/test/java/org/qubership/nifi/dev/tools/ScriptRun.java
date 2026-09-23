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
package org.qubership.nifi.dev.tools;

import java.nio.file.Path;

/**
 * Outcome of a single run of a script from {@code dev/} through {@link BashScriptRunner}.
 *
 * @param exitCode process exit code of the script
 * @param output   merged standard output and standard error of the run
 * @param workDir  directory the script ran in, where its output and temporary files land
 */
record ScriptRun(int exitCode, String output, Path workDir) {
}
