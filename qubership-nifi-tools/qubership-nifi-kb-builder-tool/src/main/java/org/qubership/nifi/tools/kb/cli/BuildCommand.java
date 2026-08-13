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

import org.qubership.nifi.tools.kb.KnowledgeBaseBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The single Knowledge Base build command. It carries the option definitions, hands them to
 * {@link BuilderConfig} for cross-option validation and secret resolution, and runs the build.
 * Failures are left to propagate so that {@link ExitCodeExceptionHandler} can map them to exit codes.
 */
@Command(name = "kb-builder",
        description = "Builds a portable, version-matched NiFi Knowledge Base from a running NiFi instance.",
        mixinStandardHelpOptions = true,
        versionProvider = BuilderVersionProvider.class,
        exitCodeOnInvalidInput = ExitCodes.USAGE,
        sortOptions = false)
public final class BuildCommand implements Callable<Integer> {

    @Option(names = "--nifi-url", required = true, paramLabel = "<https-url>",
            description = "NiFi deployment or UI URL. HTTPS is required.")
    private String nifiUrl;

    @Option(names = "--auth", required = true, paramLabel = "<token|cookie|certificate>",
            converter = AuthModeConverter.class,
            description = "Authentication mode. No option accepts a token, cookie, or password.")
    private AuthMode auth;

    @Option(names = "--certificate-file", paramLabel = "<pkcs12-path>",
            description = "PKCS#12 file with one private-key entry and its certificate chain.")
    private Path certificateFile;

    @Option(names = "--ca-file", paramLabel = "<pem-path>",
            description = "PEM file with trusted CA certificates. Omit to use the JVM trust store.")
    private Path caFile;

    @Option(names = "--output-dir", required = true, paramLabel = "<directory>",
            description = "Destination directory, replaced only after a validated build.")
    private Path outputDir;

    @Option(names = "--skip-guides",
            description = "Build the component catalog without requesting or processing the guides.")
    private boolean skipGuides;

    private final Environment environment;
    private final KnowledgeBaseBuilder builder;

    /**
     * Creates the command.
     *
     * @param environmentSource      the source of the environment variables holding the secrets
     * @param knowledgeBaseBuilder   the builder this command drives
     */
    public BuildCommand(final Environment environmentSource, final KnowledgeBaseBuilder knowledgeBaseBuilder) {
        this.environment = environmentSource;
        this.builder = knowledgeBaseBuilder;
    }

    /**
     * Resolves the configuration and runs the build.
     *
     * @return the success exit code
     */
    @Override
    public Integer call() {
        builder.run(BuilderConfig.resolve(this, environment));
        return ExitCodes.SUCCESS;
    }

    String nifiUrl() {
        return nifiUrl;
    }

    AuthMode auth() {
        return auth;
    }

    Path certificateFile() {
        return certificateFile;
    }

    Path caFile() {
        return caFile;
    }

    Path outputDir() {
        return outputDir;
    }

    boolean skipGuides() {
        return skipGuides;
    }
}
