package io.github.hanhy06.betterconsole.console;

import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.commands.SuggestionBox;
import net.minecraft.server.dedicated.DedicatedServer;
import org.jline.reader.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CommandCompleter implements Completer {
    private static final int COMPLETION_TIMEOUT_SECONDS = 2;

    private final DedicatedServer server;

    CommandCompleter(DedicatedServer server) {
        this.server = server;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        int wordCursor = line.wordCursor();
        int wordLength = line.word().length();
        if (line instanceof CompletingParsedLine completingLine) {
            wordCursor = completingLine.rawWordCursor();
            wordLength = completingLine.rawWordLength();
        }

        String input = line.line();
        int cursor = line.cursor();
        int wordStart = cursor - wordCursor;
        int wordEnd = wordStart + wordLength;
        boolean atLineEnd = cursor == input.length();

        CompletableFuture<List<Candidate>> future = server.submit(() -> {
            var source = new ConsoleSuggestionContext(server.createCommandSourceStack());
            SuggestionBox suggestionBox = server.getCommands().suggestionBox();
            return suggestionBox.parse(input, source).computeSuggestions(cursor).thenApplyAsync(suggestions -> {
                List<Candidate> matches = new ArrayList<>();
                for (Suggestion suggestion : suggestions.getList()) {
                    int start = suggestion.getRange().getStart();
                    int end = suggestion.getRange().getEnd();
                    if (start < wordStart || end > wordEnd) continue;

                    String text = suggestion.getText();
                    String value = input.substring(wordStart, start) + text + input.substring(end, wordEnd);
                    var tooltip = suggestion.getTooltip();
                    String description = tooltip == null ? null : tooltip.getString();
                    boolean appendSpace = atLineEnd && needsArgumentSeparator(suggestionBox, source, suggestion.apply(input));
                    matches.add(new Candidate(value, text, null, description, null, null, appendSpace));
                }
                return matches;
            }, server);
        }).thenCompose(result -> result);

        try {
            candidates.addAll(future.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
        }
    }

    private static boolean needsArgumentSeparator(SuggestionBox suggestionBox, ConsoleSuggestionContext source, String command) {
        var parse = suggestionBox.parse(command, source).parseResults();
        if (parse.getReader().canRead() || !parse.getExceptions().isEmpty()) return false;

        var context = parse.getContext().getLastChild();
        if (context.getCommand() != null || context.getNodes().isEmpty()) return false;

        var lastNode = context.getNodes().getLast().getNode();
        var redirect = lastNode.getRedirect();
        var parent = redirect == null ? lastNode : redirect;
        return parent.getChildren().stream().anyMatch(child -> child.canUse(source));
    }
}
