package com.guildworkman.api.signing.custody;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * Captures everything the application logs while a block runs, so a test can
 * assert on it.
 *
 * <p>Attached to the <b>root</b> logger at {@code TRACE}, deliberately: the
 * point of these tests is that a secret doesn't reach the log by <em>any</em>
 * route, and pinning the capture to one class's logger would miss a seed
 * echoed by a library, a stack trace, or a class added later. Verbosity is
 * raised for the duration too, since a leak at {@code DEBUG} is still a leak —
 * production log levels are a deployment setting, not a security control.
 */
final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level originalLevel;

    private LogCapture() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.originalLevel = root.getLevel();
        appender.setContext(context);
        appender.start();
        root.setLevel(Level.TRACE);
        root.addAppender(appender);
    }

    static LogCapture start() {
        return new LogCapture();
    }

    /** Everything logged so far: formatted messages, plus any throwable's message. */
    String output() {
        return appender.list.stream()
                .map(event -> {
                    String rendered = event.getFormattedMessage();
                    if (event.getThrowableProxy() != null) {
                        rendered += " | " + event.getThrowableProxy().getClassName()
                                + ": " + event.getThrowableProxy().getMessage();
                    }
                    return rendered;
                })
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(originalLevel);
        appender.stop();
    }
}
