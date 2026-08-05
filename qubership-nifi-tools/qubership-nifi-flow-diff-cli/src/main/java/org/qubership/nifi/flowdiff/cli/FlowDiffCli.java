package org.qubership.nifi.flowdiff.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.IVersionProvider;

/**
 * Entry point of the flow-diff command line. The three subcommands mirror the goals of
 * {@code qubership-nifi-flow-diff-tool}: {@code diff} and {@code git-diff} are read-only and emit a report,
 * {@code git-revert-technical} rewrites the working copy in place.
 *
 * <p>Finding changes is never a failure, so a run that completes exits {@code 0}. An execution error - malformed
 * input, an unresolvable branch, a duplicate identifier - exits {@code 1}, and a usage error exits {@code 2}.
 */
@Command(name = "nifi-flow-diff",
        mixinStandardHelpOptions = true,
        versionProvider = FlowDiffCli.ImplementationVersionProvider.class,
        subcommands = {DiffCommand.class, GitDiffCommand.class, GitRevertTechnicalCommand.class},
        description = "Classify the differences between Apache NiFi versioned flow exports, and restore the "
                + "technical identifiers NiFi rewrites when a flow is copied or recreated.")
public final class FlowDiffCli {

    private FlowDiffCli() {
    }

    /**
     * Runs the command line and exits with its status.
     *
     * @param args the command-line arguments
     */
    public static void main(final String[] args) {
        System.exit(run(args));
    }

    /**
     * Runs the command line and returns its exit code, without terminating the JVM. Tests drive this rather than
     * {@link #main(String[])}.
     *
     * @param args the command-line arguments
     * @return the exit code
     */
    public static int run(final String... args) {
        return new CommandLine(new FlowDiffCli())
                .setExecutionExceptionHandler(FlowDiffCli::reportFailure)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
    }

    /**
     * Prints the message of a failure without a stack trace, since every exception the services raise names the file,
     * path, or option at fault.
     *
     * @param ex          the failure raised while the subcommand ran
     * @param command     the command line that was executing
     * @param parseResult the parsed arguments, unused
     * @return the exit code to report
     */
    private static int reportFailure(final Exception ex, final CommandLine command,
            final CommandLine.ParseResult parseResult) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        command.getErr().println(command.getColorScheme().errorText(message));
        return ExitCode.SOFTWARE;
    }

    /** Reports the version recorded in the jar manifest at build time. */
    static final class ImplementationVersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = FlowDiffCli.class.getPackage().getImplementationVersion();
            return new String[] {"nifi-flow-diff " + (version == null ? "(development build)" : version)};
        }
    }
}
