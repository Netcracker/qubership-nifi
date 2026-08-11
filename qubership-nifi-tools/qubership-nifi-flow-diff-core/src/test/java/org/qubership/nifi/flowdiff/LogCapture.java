package org.qubership.nifi.flowdiff;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures what a class logs, so a test can assert on a warning the way it would once have verified a mocked logger.
 * Attach it in a try-with-resources block: closing detaches the appender, leaving the logger as it was found.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(final Logger loggerValue) {
        this.logger = loggerValue;
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Starts capturing the events logged by a class.
     *
     * @param type the class whose logger to capture
     * @return the capture, to be closed when the assertions are done
     */
    public static LogCapture on(final Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type));
    }

    /**
     * Returns the formatted messages captured so far, in order.
     *
     * @return the captured messages
     */
    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Tells whether a warning containing the given text was logged.
     *
     * @param fragment the text to look for
     * @return {@code true} when a warning contains it
     */
    public boolean warnedAbout(final String fragment) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
