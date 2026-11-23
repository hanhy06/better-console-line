package io.github.hanhy06.betterconsole.console;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.suggestions.SuggestionContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

record ConsoleSuggestionContext(CommandSourceStack source) implements SuggestionContext {
    @Override
    public MinecraftServer server() {
        return source.getServer();
    }

    @Override
    public PermissionSet permissions() {
        return source.permissions();
    }

    @Override
    public Level level() {
        return source.getLevel();
    }

    @Override
    public Set<ResourceKey<Level>> levels() {
        return source.levels();
    }

    @Override
    public Entity entity() {
        return source.getEntity();
    }

    @Override
    public Vec3 position() {
        return source.getPosition();
    }

    @Override
    public HolderLookup.Provider holderLookups() {
        return source.holderLookups();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return source.enabledFeatures();
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        return List.of(server().getPlayerNames());
    }

    @Override
    public Collection<String> allTeams() {
        return server().getScoreboard().getTeamNames();
    }

    @Override
    public Stream<Identifier> availableSounds() {
        return BuiltInRegistries.SOUND_EVENT.keySet().stream();
    }

    @Override
    public Stream<Identifier> availablePostEffects() {
        return Stream.empty();
    }

    @Override
    public CompletableFuture<Suggestions> suggestObjectiveNames(CommandContext<?> context, SuggestionsBuilder builder) {
        return SuggestionContext.suggest(server().getScoreboard().getObjectiveNames(), builder);
    }

    @Override
    public CompletableFuture<Suggestions> customSuggestion(CommandContext<?> context) {
        return Suggestions.empty();
    }

    @Override
    public <E> CompletableFuture<Suggestions> suggestRegistryElements(CommandContext<?> context,
            ResourceKey<? extends Registry<? extends E>> registry, ElementSuggestionType type,
            SuggestionsBuilder builder, Predicate<E> filter) {
        holderLookups().lookup(registry).ifPresent(lookup -> suggestRegistryElements(lookup, type, builder, filter));
        return builder.buildFuture();
    }
}
