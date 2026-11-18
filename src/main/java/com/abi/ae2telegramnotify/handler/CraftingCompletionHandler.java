package com.abi.ae2telegramnotify.handler;

import appeng.api.features.IPlayerRegistry;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.abi.ae2telegramnotify.Config;
import com.abi.ae2telegramnotify.mixin.ExecutingCraftingJobAccessor;
import com.abi.ae2telegramnotify.storage.PlayerTelegramStore;
import com.abi.ae2telegramnotify.telegram.TelegramClient;
import com.abi.ae2telegramnotify.telegram.TelegramMessageFormatter;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

public final class CraftingCompletionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingCompletionHandler.class);

    private CraftingCompletionHandler() {
    }

    public static void handleJobFinished(CraftingCPUCluster cluster, ExecutingCraftingJob job) {
        try {
            if (!Config.ENABLED.get()) {
                return;
            }

            String botToken = Config.BOT_TOKEN.get();
            if (botToken == null || botToken.isBlank()) {
                return;
            }

            if (!(cluster.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) (Object) job;
            Integer playerId = accessor.ae2tg$getPlayerId();
            if (playerId == null) {
                return;
            }

            MinecraftServer server = serverLevel.getServer();
            UUID playerUuid = IPlayerRegistry.getMapping(server).getProfileId(playerId);
            if (playerUuid == null) {
                LOGGER.debug("Unknown AE2 playerId={} for finished crafting job", playerId);
                return;
            }

            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerUuid);
            if (onlinePlayer != null) {
                if (Config.IGNORE_FAKE_PLAYERS.get() && onlinePlayer instanceof FakePlayer) {
                    return;
                }
                if (Config.ONLY_WHEN_PLAYER_OFFLINE.get()) {
                    return;
                }
            }

            GenericStack finalOutput = accessor.ae2tg$getFinalOutput();
            if (finalOutput == null) {
                return;
            }

            long amount = finalOutput.amount();
            if (amount < Config.MIN_AMOUNT.get()) {
                return;
            }

            ElapsedTimeTracker tracker = accessor.ae2tg$getTimeTracker();
            long elapsedNanos = tracker != null ? Math.max(0L, tracker.getElapsedTime()) : 0L;
            long minElapsedNanos = Config.MIN_ELAPSED_SECONDS.get() * 1_000_000_000L;
            if (elapsedNanos < minElapsedNanos) {
                return;
            }

            PlayerTelegramStore store = PlayerTelegramStore.getInstance();
            Optional<String> chatIdOpt = store.resolveChatId(playerUuid, Config.DEFAULT_CHAT_ID.get());
            if (chatIdOpt.isEmpty()) {
                return;
            }

            String playerName = resolvePlayerName(server, store, playerUuid, onlinePlayer);
            if (onlinePlayer != null) {
                store.updateKnownName(playerUuid, playerName);
            }

            String itemName = finalOutput.what().getDisplayName().getString();
            String formattedAmount = finalOutput.what().formatAmount(amount, AmountFormat.FULL);
            Component cpuNameComponent = cluster.getName();
            String cpuName = cpuNameComponent != null ? cpuNameComponent.getString() : null;
            String dimensionId = serverLevel.dimension().location().toString();

            String message = TelegramMessageFormatter.formatCompletionMessage(
                    Config.LANGUAGE.get(),
                    itemName,
                    formattedAmount,
                    playerName,
                    cpuName,
                    elapsedNanos,
                    dimensionId,
                    Config.INCLUDE_CPU_NAME.get(),
                    Config.INCLUDE_DURATION.get(),
                    Config.INCLUDE_DIMENSION.get()
            );

            String chatId = chatIdOpt.get();
            TelegramClient.sendMessageAsync(
                    Config.API_BASE_URL.get(),
                    botToken,
                    chatId,
                    message,
                    Config.REQUEST_TIMEOUT_SECONDS.get(),
                    Config.proxyConfig()
            ).thenAccept(result -> {
                if (!result.ok()) {
                    LOGGER.warn("Failed to deliver AE2 Telegram notification to chatId={} for player={}: {}",
                            chatId, playerName, result.error());
                }
            });
        } catch (Exception e) {
            LOGGER.error("Unexpected error handling AE2 crafting completion", e);
        }
    }

    private static String resolvePlayerName(
            MinecraftServer server,
            PlayerTelegramStore store,
            UUID playerUuid,
            @Nullable ServerPlayer onlinePlayer
    ) {
        if (onlinePlayer != null) {
            return onlinePlayer.getGameProfile().getName();
        }
        PlayerTelegramStore.PlayerBinding binding = store.getBinding(playerUuid);
        if (binding != null && binding.lastKnownName != null && !binding.lastKnownName.isBlank()) {
            return binding.lastKnownName;
        }
        if (server.getProfileCache() != null) {
            Optional<GameProfile> cached = server.getProfileCache().get(playerUuid);
            if (cached.isPresent() && cached.get().getName() != null) {
                return cached.get().getName();
            }
        }
        return playerUuid.toString().substring(0, 8);
    }
}
