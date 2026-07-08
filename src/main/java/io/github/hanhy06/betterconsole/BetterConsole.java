package io.github.hanhy06.betterconsole;

import io.github.hanhy06.betterconsole.console.ConsoleManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterConsole implements ModInitializer {
    public static final String MOD_ID = "better-console-line";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ConsoleManager.initialize();
        ServerLifecycleEvents.SERVER_STOPPING.register(ConsoleManager::stop);
    }
}
