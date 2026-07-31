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
import org.qubership.nifi.tools.kb.KnowledgeBaseBuilder;
import org.qubership.nifi.tools.kb.TransportFactory;
import org.qubership.nifi.tools.kb.collect.ComponentCollector;
import org.qubership.nifi.tools.kb.docs.DeveloperGuideExtractor;
import org.qubership.nifi.tools.kb.docs.GuideCollector;
import org.qubership.nifi.tools.kb.docs.HtmlToMarkdown;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseValidator;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseWriter;
import org.qubership.nifi.tools.kb.output.OutputReplacer;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Command-line entry point for the Knowledge Base builder. The command line is assembled here rather
 * than left to picocli defaults so that both output streams can be pinned to standard error: standard
 * output is reserved for consumers that pipe it, and usage, version, and diagnostics must not appear
 * there.
 *
 * <p>The collaborators are wired by hand. The graph is small and every dependency is known at build
 * time, so a container would buy nothing that this method does not already state plainly.</p>
 */
public final class KnowledgeBaseBuilderApplication {

    private KnowledgeBaseBuilderApplication() {
        // entry point
    }

    /**
     * Application entry point.
     *
     * @param args the command-line arguments
     */
    public static void main(final String[] args) {
        System.exit(run(args));
    }

    /**
     * Runs the command line and returns the process exit code.
     *
     * @param args the command-line arguments
     * @return the exit code
     */
    static int run(final String... args) {
        final PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        return new CommandLine(newCommand())
                .setOut(err)
                .setErr(err)
                .setExecutionExceptionHandler(new ExitCodeExceptionHandler())
                .execute(args);
    }

    private static BuildCommand newCommand() {
        final KnowledgeBaseBuilder builder = new KnowledgeBaseBuilder(
                BuilderVersion.load(),
                new TransportFactory(),
                new ComponentCollector(),
                new GuideCollector(new HtmlToMarkdown(), new DeveloperGuideExtractor()),
                new KnowledgeBaseWriter(),
                new KnowledgeBaseValidator(),
                new OutputReplacer());
        return new BuildCommand(Environment.system(), builder);
    }
}
