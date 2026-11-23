package io.github.hanhy06.betterconsole.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.hanhy06.betterconsole.console.ConsoleManager;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @WrapOperation(
            method = "initServer",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V")
    )
    private void replaceConsoleThread(Thread thread, Operation<Void> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        if (!thread.getName().equals("Server console handler") || !ConsoleManager.start(server)) {
            original.call(thread);
        }
    }
}
