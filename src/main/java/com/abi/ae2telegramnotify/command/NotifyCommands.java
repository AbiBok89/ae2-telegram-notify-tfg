package com.abi.ae2telegramnotify.command;

import com.abi.ae2telegramnotify.Config;
import com.abi.ae2telegramnotify.storage.PlayerTelegramStore;
import com.abi.ae2telegramnotify.telegram.TelegramClient;
import com.abi.ae2telegramnotify.telegram.TelegramMessageFormatter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public final class NotifyCommands {

    private NotifyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aenotify")
                .then(Commands.literal("bind")
                        .then(Commands.argument("chat_id", StringArgumentType.word())
                                .executes(NotifyCommands::bindSelf)))
                .then(Commands.literal("link")
                        .executes(NotifyCommands::startLink))
                .then(Commands.literal("verify")
                        .executes(NotifyCommands::verifyLink))
                .then(Commands.literal("unbind")
                        .executes(NotifyCommands::unbindSelf))
                .then(Commands.literal("toggle")
                        .executes(NotifyCommands::toggleSelf))
                .then(Commands.literal("status")
                        .executes(NotifyCommands::statusSelf))
                .then(Commands.literal("test")
                        .executes(NotifyCommands::testSelf))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("bind")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("chat_id", StringArgumentType.word())
                                                .executes(NotifyCommands::adminBind))))
                        .then(Commands.literal("unbind")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(NotifyCommands::adminUnbind)))
                        .then(Commands.literal("status")
                                .executes(NotifyCommands::adminStatus)))
        );
    }

    private static int bindSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String chatId = StringArgumentType.getString(ctx, "chat_id").trim();
        if (!isValidChatId(chatId)) {
            ctx.getSource().sendFailure(Component.literal(
                    "Некорректный chat_id. Пример: 123456789 или -1001234567890.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        PlayerTelegramStore.getInstance().bind(player.getUUID(), player.getGameProfile().getName(), chatId);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Telegram привязан к chat_id: " + chatId + ". Проверьте отправку командой /aenotify test")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int startLink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String botToken = Config.BOT_TOKEN.get();
        if (botToken == null || botToken.isBlank()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Бот ещё не настроен на сервере (botToken пуст в конфиге ae2telegramnotify-common.toml).")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String code = PlayerTelegramStore.getInstance().createLinkCode(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Код привязки: " + code + " (действует 5 минут).\n"
                        + "1) Откройте вашего Telegram-бота и отправьте ему: " + code + " (или /start " + code + ")\n"
                        + "2) Затем выполните в игре: /aenotify verify")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int verifyLink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerTelegramStore store = PlayerTelegramStore.getInstance();
        PlayerTelegramStore.PendingLink pending = store.getValidLinkCode(player.getUUID());
        if (pending == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Нет активного кода привязки. Сначала выполните /aenotify link")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String botToken = Config.BOT_TOKEN.get();
        if (botToken == null || botToken.isBlank()) {
            ctx.getSource().sendFailure(Component.literal(
                    "botToken не указан в конфиге сервера.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Проверяем сообщения у Telegram-бота...")
                .withStyle(ChatFormatting.GRAY), false);

        MinecraftServer server = ctx.getSource().getServer();
        String apiBaseUrl = Config.API_BASE_URL.get();
        int timeout = Config.REQUEST_TIMEOUT_SECONDS.get();
        String language = Config.LANGUAGE.get();

        TelegramClient.findChatByLinkCodeAsync(apiBaseUrl, botToken, pending.code(), timeout)
                .thenAccept(pollResult -> server.execute(() -> {
                    if (pollResult.error() != null) {
                        player.sendSystemMessage(Component.literal(
                                "Ошибка Telegram API: " + pollResult.error())
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    if (!pollResult.found() || pollResult.chatId() == null) {
                        player.sendSystemMessage(Component.literal(
                                "Сообщение с кодом " + pending.code() + " пока не найдено. "
                                        + "Убедитесь, что отправили его боту, и повторите /aenotify verify")
                                .withStyle(ChatFormatting.YELLOW));
                        return;
                    }

                    String chatId = pollResult.chatId();
                    String playerName = player.getGameProfile().getName();
                    store.bind(player.getUUID(), playerName, chatId);

                    player.sendSystemMessage(Component.literal(
                            "Telegram успешно привязан! (" + pollResult.telegramLabel() + ", chat_id: " + chatId + ")")
                            .withStyle(ChatFormatting.GREEN));

                    String confirmMsg = TelegramMessageFormatter.isRussian(language)
                            ? "✅ Аккаунт Minecraft <b>" + TelegramMessageFormatter.escapeHtml(playerName)
                            + "</b> успешно привязан к этому чату!"
                            : "✅ Minecraft account <b>" + TelegramMessageFormatter.escapeHtml(playerName)
                            + "</b> has been linked to this chat!";
                    TelegramClient.sendMessageAsync(apiBaseUrl, botToken, chatId, confirmMsg, timeout);
                }));
        return 1;
    }

    private static int unbindSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean removed = PlayerTelegramStore.getInstance().unbind(player.getUUID());
        if (removed) {
            ctx.getSource().sendSuccess(() -> Component.literal("Привязка Telegram удалена.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("У вас нет привязанного Telegram.")
                .withStyle(ChatFormatting.YELLOW));
        return 0;
    }

    private static int toggleSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerTelegramStore store = PlayerTelegramStore.getInstance();
        if (store.getBinding(player.getUUID()) == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Сначала привяжите Telegram через /aenotify link или /aenotify bind <chat_id>.")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        boolean nowEnabled = store.toggleEnabled(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                nowEnabled ? "Уведомления Telegram включены." : "Уведомления Telegram поставлены на паузу.")
                .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int statusSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerTelegramStore store = PlayerTelegramStore.getInstance();
        PlayerTelegramStore.PlayerBinding binding = store.getBinding(player.getUUID());

        boolean botConfigured = Config.BOT_TOKEN.get() != null && !Config.BOT_TOKEN.get().isBlank();
        String defaultChat = Config.DEFAULT_CHAT_ID.get();

        StringBuilder msg = new StringBuilder();
        msg.append("=== AE2 Telegram Notify ===\n");
        msg.append("Мод включён: ").append(Config.ENABLED.get() ? "да" : "нет").append("\n");
        msg.append("Бот настроен: ").append(botConfigured ? "да" : "нет (укажите botToken в конфиге)").append("\n");
        if (binding != null) {
            msg.append("Ваш chat_id: ").append(binding.chatId).append("\n");
            msg.append("Ваши уведомления: ").append(binding.enabled ? "включены" : "на паузе");
        } else if (defaultChat != null && !defaultChat.isBlank()) {
            msg.append("Персональный chat_id не задан (используется общий defaultChatId: ").append(defaultChat).append(")");
        } else {
            msg.append("Telegram не привязан. Используйте /aenotify link или /aenotify bind <chat_id>");
        }

        ctx.getSource().sendSuccess(() -> Component.literal(msg.toString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int testSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String botToken = Config.BOT_TOKEN.get();
        if (botToken == null || botToken.isBlank()) {
            ctx.getSource().sendFailure(Component.literal(
                    "botToken не указан в конфиге config/ae2telegramnotify-common.toml")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<String> chatIdOpt = PlayerTelegramStore.getInstance()
                .resolveChatId(player.getUUID(), Config.DEFAULT_CHAT_ID.get());
        if (chatIdOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Не найден chat_id (привяжите через /aenotify link или /aenotify bind <chat_id>, либо снимите паузу /aenotify toggle).")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String chatId = chatIdOpt.get();
        String playerName = player.getGameProfile().getName();
        String text = TelegramMessageFormatter.formatTestMessage(Config.LANGUAGE.get(), playerName, chatId);
        MinecraftServer server = ctx.getSource().getServer();

        ctx.getSource().sendSuccess(() -> Component.literal("Отправка тестового сообщения в Telegram...")
                .withStyle(ChatFormatting.GRAY), false);

        TelegramClient.sendMessageAsync(
                Config.API_BASE_URL.get(),
                botToken,
                chatId,
                text,
                Config.REQUEST_TIMEOUT_SECONDS.get()
        ).thenAccept(result -> server.execute(() -> {
            if (result.ok()) {
                player.sendSystemMessage(Component.literal(
                        "Тестовое уведомление отправлено в Telegram (chat_id: " + chatId + ")!")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                player.sendSystemMessage(Component.literal(
                        "Ошибка отправки в Telegram: " + result.error())
                        .withStyle(ChatFormatting.RED));
            }
        }));
        return 1;
    }

    private static int adminBind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String chatId = StringArgumentType.getString(ctx, "chat_id").trim();
        if (!isValidChatId(chatId)) {
            ctx.getSource().sendFailure(Component.literal("Некорректный chat_id.").withStyle(ChatFormatting.RED));
            return 0;
        }
        PlayerTelegramStore.getInstance().bind(target.getUUID(), target.getGameProfile().getName(), chatId);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Привязан игрок " + target.getGameProfile().getName() + " -> chat_id " + chatId)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int adminUnbind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean removed = PlayerTelegramStore.getInstance().unbind(target.getUUID());
        if (removed) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Отвязан игрок " + target.getGameProfile().getName())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("У игрока нет привязки.").withStyle(ChatFormatting.YELLOW));
        return 0;
    }

    private static int adminStatus(CommandContext<CommandSourceStack> ctx) {
        int count = PlayerTelegramStore.getInstance().getAllBindings().size();
        boolean hasToken = Config.BOT_TOKEN.get() != null && !Config.BOT_TOKEN.get().isBlank();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "AE2 Telegram Notify | enabled=" + Config.ENABLED.get()
                        + " | botConfigured=" + hasToken
                        + " | boundPlayers=" + count)
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static boolean isValidChatId(String chatId) {
        if (chatId.isBlank()) {
            return false;
        }
        if (chatId.startsWith("@") && chatId.length() > 1) {
            return true;
        }
        return chatId.matches("-?\\d{1,20}");
    }
}
