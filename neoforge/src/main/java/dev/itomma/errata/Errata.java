package dev.itomma.errata;

import dev.itomma.errata.core.ErrataCommands;
import dev.itomma.errata.core.ErrataCore;
import dev.itomma.errata.core.RepairService;
import dev.itomma.errata.core.UnlockHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

/**
 * NeoForge entrypoint. The mirror of {@code dev.itomma.errata.fabric.ErrataFabric}: it owns the
 * lifecycle, translates NeoForge events into calls on the core, and nothing else.
 *
 * <p>The command tree itself lives in {@link ErrataCommands}, since Brigadier is vanilla and both
 * loaders register exactly the same commands.
 */
@Mod(Errata.MOD_ID)
public class Errata {

    public static final String MOD_ID = ErrataCore.MOD_ID;
    public static final Logger LOGGER = ErrataCore.LOGGER;

    public Errata(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ErrataCore.bind(Config.VALUES);
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static final class GameEvents {

        /** Set once per server start so a repair can never bounce the reload in a loop. */
        private static boolean repairAttempted = false;

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            MinecraftServer server = event.getServer();
            UnlockHandler.rebuild(server);

            if (!repairAttempted && ErrataCore.config().repairBrokenAdvancements()) {
                repairAttempted = true;
                RepairService.run(server);
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            repairAttempted = false;
            UnlockHandler.clear();
        }

        /**
         * Fires once with a null player after every datapack reload, which is exactly when
         * the recipe list may have changed underneath us.
         */
        @SubscribeEvent
        public static void onDatapackSync(OnDatapackSyncEvent event) {
            if (event.getPlayer() == null) {
                UnlockHandler.rebuild(event.getPlayerList().getServer());
            }
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                UnlockHandler.onPlayerJoin(player);
            }
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                UnlockHandler.onPlayerLeave(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            int interval = ErrataCore.config().scanIntervalTicks();
            if (player.tickCount % interval != 0) {
                return;
            }
            UnlockHandler.scan(player);
            UnlockHandler.tickSync(player);
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            ErrataCommands.register(event.getDispatcher(), event.getBuildContext());
        }
    }
}
