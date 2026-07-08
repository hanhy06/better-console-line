package io.github.hanhy06.betterconsole.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ConsoleLogAppender extends AbstractAppender {
    private static final String APPENDER_NAME = "BetterConsoleLine";
    private static final int QUEUE_CAPACITY = 8192;
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final PrintStream STANDARD_OUT = System.out;

    private final LineReader reader;
    private final boolean colorEnabled;
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedMessages = new AtomicLong();
    private final Thread outputThread;
    private final LoggerContext context;
    private final Appender originalAppender;
    private final List<AppenderBinding> bindings;

    private volatile boolean running = true;

    private ConsoleLogAppender(
            LineReader reader,
            boolean colorEnabled,
            LoggerContext context,
            ConsoleAppender originalAppender,
            List<AppenderBinding> bindings
    ) {
        super(APPENDER_NAME, originalAppender.getFilter(), originalAppender.getLayout(), true, Property.EMPTY_ARRAY);
        this.reader = reader;
        this.colorEnabled = colorEnabled;
        this.context = context;
        this.originalAppender = originalAppender;
        this.bindings = bindings;
        this.outputThread = Thread.ofPlatform()
                .name("Better Console Line output")
                .daemon(true)
                .unstarted(this::writeMessages);
    }

    public static ConsoleLogAppender install(LineReader reader, Terminal terminal) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        ConsoleAppender originalAppender = findConsoleAppender(configuration);
        if (originalAppender == null || originalAppender.getLayout() == null) return null;

        List<AppenderBinding> bindings = findBindings(configuration, originalAppender.getName());
        if (bindings.isEmpty()) return null;

        ConsoleLogAppender appender = new ConsoleLogAppender(
                reader,
                isColorEnabled(terminal),
                context,
                originalAppender,
                bindings
        );
        appender.start();
        configuration.addAppender(appender);

        for (AppenderBinding binding : bindings) {
            binding.logger().removeAppender(originalAppender.getName());
            binding.logger().addAppender(appender, binding.level(), binding.filter());
        }
        context.updateLoggers();
        return appender;
    }

    @Override
    public void start() {
        super.start();
        outputThread.start();
    }

    @Override
    public void append(LogEvent event) {
        String text = getLayout().toSerializable(event).toString();
        if (colorEnabled && text.indexOf('\u001B') < 0) text = addColors(event.getLevel(), text);

        if (!messages.offer(text)) droppedMessages.incrementAndGet();
    }

    private static String addColors(Level level, String text) {
        int timeEnd = text.indexOf(']');
        int threadStart = text.indexOf('[', timeEnd + 1);
        int threadEnd = text.indexOf(']', threadStart + 1);
        String levelColor = getLevelColor(level);
        if (!text.startsWith("[") || timeEnd < 0 || threadStart < 0 || threadEnd < 0) {
            return levelColor == null ? text : levelColor + text + ANSI_RESET;
        }

        String threadColor = levelColor == null ? ANSI_GREEN : levelColor;
        String result = ANSI_CYAN + text.substring(0, timeEnd + 1) + ANSI_RESET
                + text.substring(timeEnd + 1, threadStart)
                + threadColor + text.substring(threadStart, threadEnd + 1) + ANSI_RESET;
        if (levelColor == null) return result + text.substring(threadEnd + 1);
        return result + levelColor + text.substring(threadEnd + 1) + ANSI_RESET;
    }

    private static String getLevelColor(Level level) {
        if (level.isMoreSpecificThan(Level.ERROR)) return ANSI_RED;
        if (level.isMoreSpecificThan(Level.WARN)) return ANSI_YELLOW;
        return null;
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        running = false;
        outputThread.interrupt();

        for (AppenderBinding binding : bindings) {
            binding.logger().removeAppender(getName());
            binding.logger().addAppender(originalAppender, binding.level(), binding.filter());
        }
        context.updateLoggers();

        try {
            outputThread.join(timeUnit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return super.stop(timeout, timeUnit);
    }

    private void writeMessages() {
        while (running || !messages.isEmpty()) {
            String text = null;
            try {
                text = messages.poll(250, TimeUnit.MILLISECONDS);
                printDroppedMessages();
                if (text != null) reader.printAbove(text);
            } catch (InterruptedException ignored) {
            } catch (RuntimeException ignored) {
                if (text != null) STANDARD_OUT.print(text);
            }
        }
        printDroppedMessages();
    }

    private void printDroppedMessages() {
        long dropped = droppedMessages.getAndSet(0);
        if (dropped == 0) return;

        reader.printAbove("[Better Console Line] Console log messages omitted: " + dropped + System.lineSeparator());
    }

    private static ConsoleAppender findConsoleAppender(Configuration configuration) {
        for (Appender appender : configuration.getAppenders().values()) {
            if (appender instanceof ConsoleAppender consoleAppender) return consoleAppender;
        }
        return null;
    }

    private static List<AppenderBinding> findBindings(Configuration configuration, String appenderName) {
        List<AppenderBinding> bindings = new ArrayList<>();
        addBinding(bindings, configuration.getRootLogger(), appenderName);
        for (LoggerConfig logger : configuration.getLoggers().values()) {
            addBinding(bindings, logger, appenderName);
        }
        return bindings;
    }

    private static void addBinding(List<AppenderBinding> bindings, LoggerConfig logger, String appenderName) {
        for (AppenderRef reference : logger.getAppenderRefs()) {
            if (!reference.getRef().equals(appenderName)) continue;
            bindings.add(new AppenderBinding(logger, reference.getLevel(), reference.getFilter()));
        }
    }

    private static boolean isColorEnabled(Terminal terminal) {
        String configured = System.getProperty("better-console-line.color", "auto");
        if (configured.equalsIgnoreCase("true")) return true;
        if (configured.equalsIgnoreCase("false")) return false;
        if (System.getenv("NO_COLOR") != null) return false;

        String type = terminal.getType();
        return type != null && !type.startsWith(Terminal.TYPE_DUMB);
    }

    private record AppenderBinding(LoggerConfig logger, Level level, Filter filter) {
    }
}
