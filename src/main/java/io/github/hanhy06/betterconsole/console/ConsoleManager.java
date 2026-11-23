package io.github.hanhy06.betterconsole.console;

import io.github.hanhy06.betterconsole.BetterConsole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class ConsoleManager {
    private static final Path HISTORY_FILE = Path.of("config", "better-console-line", "history");
    private static final int HISTORY_SIZE = 500;
    private static final int HISTORY_FILE_SIZE = 2000;

    private static ConsoleManager INSTANCE;

    private final DedicatedServer server;
    private final Terminal terminal;
    private final LineReader reader;
    private final ConsoleLogAppender logAppender;
    private final Thread inputThread;

    private volatile boolean running = true;

    private ConsoleManager(DedicatedServer server, Terminal terminal, LineReader reader, ConsoleLogAppender logAppender) {
        this.server = server;
        this.terminal = terminal;
        this.reader = reader;
        this.logAppender = logAppender;
        this.inputThread = Thread.ofPlatform()
                .name("Better Console Line input")
                .daemon(true)
                .unstarted(this::readCommands);
    }

    public static synchronized boolean start(DedicatedServer server) {
        if (INSTANCE != null) return INSTANCE.server == server;

        Terminal terminal = createTerminal();
        if (terminal == null) return false;

        LineReader reader = createReader(server, terminal);
        ConsoleLogAppender logAppender = ConsoleLogAppender.install(reader, terminal);
        if (logAppender == null) {
            closeTerminal(terminal);
            BetterConsole.LOGGER.warn("Unable to replace the console log appender, using the standard console");
            return false;
        }

        INSTANCE = new ConsoleManager(server, terminal, reader, logAppender);
        INSTANCE.inputThread.start();
        return true;
    }

    public static synchronized void stop(MinecraftServer server) {
        if (INSTANCE == null || INSTANCE.server != server) return;

        ConsoleManager manager = INSTANCE;
        INSTANCE = null;
        manager.close();
    }

    private void close() {
        running = false;
        inputThread.interrupt();
        waitForInputThread();

        logAppender.stop(2, TimeUnit.SECONDS);

        try {
            reader.getHistory().save();
        } catch (IOException e) {
            BetterConsole.LOGGER.warn("Failed to save console history", e);
        }

        closeTerminal(terminal);
        waitForInputThread();
    }

    private void readCommands() {

        while (running && server.isRunning() && !server.isStopped()) {
            try {
                String command = reader.readLine("> ");
                if (!command.isBlank()) server.handleConsoleInput(command, server.createCommandSourceStack());
            } catch (EndOfFileException ignored) {
            } catch (UserInterruptException e) {
                if (running) server.halt(false);
                return;
            } catch (IOError | RuntimeException e) {
                if (running) BetterConsole.LOGGER.error("Failed to read console input", e);
                return;
            }
        }
    }

    private void waitForInputThread() {
        if (Thread.currentThread() == inputThread || !inputThread.isAlive()) return;

        try {
            inputThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Terminal createTerminal() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .name("Better Console Line")
                    .system(true)
                    .providers("exec,jni,ffm")
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            if (terminal.getType() != null && terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
                closeTerminal(terminal);
                return null;
            }
            return terminal;
        } catch (IOException | IllegalStateException e) {
            BetterConsole.LOGGER.warn("Advanced console features are unavailable", e);
            return null;
        }
    }

    private static LineReader createReader(DedicatedServer server, Terminal terminal) {
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .appName("better-console-line")
                .terminal(terminal)
                .completer(new CommandCompleter(server))
                .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_FILE_SIZE);

        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            builder.variable(LineReader.HISTORY_FILE, HISTORY_FILE);
        } catch (IOException e) {
            BetterConsole.LOGGER.warn("Failed to prepare persistent console history", e);
        }

        LineReader reader = builder.build();
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        reader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
        reader.unsetOpt(LineReader.Option.INSERT_TAB);

        Widget acceptLine = reader.getWidgets().get(LineReader.ACCEPT_LINE);
        reader.getWidgets().replace(LineReader.ACCEPT_LINE, () -> {
            if (reader.getBuffer().toString().isBlank()) return true;
            return acceptLine.apply();
        });
        return reader;
    }

    private static void closeTerminal(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            BetterConsole.LOGGER.warn("Failed to restore terminal state", e);
        }
    }
}
