package org.qubership.nifi.flowdiff.cli;

import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * The options every subcommand accepts. {@code --basedir} stands in for the Maven plugin's
 * {@code ${project.basedir}}: relative input paths resolve against it, and the Git subcommands discover the enclosing
 * repository from it.
 */
abstract class AbstractFlowDiffCommand implements Callable<Integer> {

    @Option(names = "--basedir", paramLabel = "<dir>",
            description = "Directory that relative paths resolve against. Default: the working directory.")
    private File basedir = new File(System.getProperty("user.dir"));

    @Option(names = "--skip-malformed",
            description = "Continue past a malformed candidate file instead of failing.")
    private boolean skipMalformed;

    /**
     * Returns the directory relative inputs resolve against.
     *
     * @return the base directory
     */
    protected final File getBasedir() {
        return basedir;
    }

    /**
     * Tells whether malformed candidate files are skipped rather than failing the run.
     *
     * @return {@code true} when malformed files are skipped
     */
    protected final boolean isSkipMalformed() {
        return skipMalformed;
    }
}
