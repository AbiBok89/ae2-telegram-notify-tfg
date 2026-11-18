package com.abi.ae2telegramnotify;

import com.abi.ae2telegramnotify.command.NotifyCommands;
import com.abi.ae2telegramnotify.storage.PlayerTelegramStore;
import com.abi.ae2telegramnotify.telegram.TelegramClient;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Ae2TelegramNotifyMod.MOD_ID)
public class Ae2TelegramNotifyMod {
    public static final String MOD_ID = "ae2telegramnotify";
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2TelegramNotifyMod.class);

    public Ae2TelegramNotifyMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);

        LOGGER.info("AE2 Telegram Notify (Forge 1.20.1) initialized");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        NotifyCommands.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        PlayerTelegramStore.getInstance().load();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        PlayerTelegramStore.getInstance().saveIfDirty();
        TelegramClient.shutdown();
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerTelegramStore.getInstance().updateKnownName(
                    player.getUUID(),
                    player.getGameProfile().getName()
            );
        }
    }
}
