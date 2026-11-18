package com.abi.ae2telegramnotify.telegram;

import javax.annotation.Nullable;
import java.util.Locale;

public final class TelegramMessageFormatter {

    private TelegramMessageFormatter() {
    }

    public static String formatCompletionMessage(
            String language,
            String itemName,
            String formattedAmount,
            String playerName,
            @Nullable String cpuName,
            long elapsedNanos,
            @Nullable String dimensionId,
            boolean includeCpuName,
            boolean includeDuration,
            boolean includeDimension
    ) {
        boolean isRu = isRussian(language);
        StringBuilder sb = new StringBuilder();

        if (isRu) {
            sb.append("✅ <b>Заказ AE2 завершён!</b>\n\n");
            sb.append("📦 <b>Предмет:</b> ").append(escapeHtml(itemName))
                    .append(" × ").append(escapeHtml(formattedAmount)).append('\n');
            sb.append("👤 <b>Игрок:</b> ").append(escapeHtml(playerName)).append('\n');
            if (includeCpuName && cpuName != null && !cpuName.isBlank()) {
                sb.append("🖥 <b>Процессор:</b> ").append(escapeHtml(cpuName)).append('\n');
            }
            if (includeDuration && elapsedNanos > 0) {
                sb.append("⏱ <b>Время крафта:</b> ").append(escapeHtml(formatDuration(elapsedNanos, true))).append('\n');
            }
            if (includeDimension && dimensionId != null && !dimensionId.isBlank()) {
                sb.append("🌍 <b>Измерение:</b> ").append(escapeHtml(dimensionId)).append('\n');
            }
        } else {
            sb.append("✅ <b>AE2 Crafting Order Finished!</b>\n\n");
            sb.append("📦 <b>Item:</b> ").append(escapeHtml(itemName))
                    .append(" × ").append(escapeHtml(formattedAmount)).append('\n');
            sb.append("👤 <b>Player:</b> ").append(escapeHtml(playerName)).append('\n');
            if (includeCpuName && cpuName != null && !cpuName.isBlank()) {
                sb.append("🖥 <b>CPU:</b> ").append(escapeHtml(cpuName)).append('\n');
            }
            if (includeDuration && elapsedNanos > 0) {
                sb.append("⏱ <b>Duration:</b> ").append(escapeHtml(formatDuration(elapsedNanos, false))).append('\n');
            }
            if (includeDimension && dimensionId != null && !dimensionId.isBlank()) {
                sb.append("🌍 <b>Dimension:</b> ").append(escapeHtml(dimensionId)).append('\n');
            }
        }

        return sb.toString().trim();
    }

    public static String formatTestMessage(String language, String playerName, String chatId) {
        if (isRussian(language)) {
            return "🔔 <b>Тестовое уведомление AE2 Telegram Notify</b>\n\n"
                    + "👤 <b>Игрок:</b> " + escapeHtml(playerName) + "\n"
                    + "💬 <b>Chat ID:</b> <code>" + escapeHtml(chatId) + "</code>\n"
                    + "Всё настроено и работает корректно!";
        }
        return "🔔 <b>AE2 Telegram Notify Test Message</b>\n\n"
                + "👤 <b>Player:</b> " + escapeHtml(playerName) + "\n"
                + "💬 <b>Chat ID:</b> <code>" + escapeHtml(chatId) + "</code>\n"
                + "Everything is configured and working!";
    }

    public static String formatDuration(long elapsedNanos, boolean russian) {
        long totalSeconds = Math.max(0L, elapsedNanos / 1_000_000_000L);
        if (totalSeconds == 0) {
            long millis = Math.max(1L, elapsedNanos / 1_000_000L);
            return russian ? millis + " мс" : millis + "ms";
        }

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(russian ? " ч. " : "h ");
        }
        if (minutes > 0 || hours > 0) {
            sb.append(minutes).append(russian ? " мин. " : "m ");
        }
        sb.append(seconds).append(russian ? " сек." : "s");
        return sb.toString().trim();
    }

    public static String escapeHtml(@Nullable String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static boolean isRussian(@Nullable String language) {
        return language == null || language.trim().toLowerCase(Locale.ROOT).startsWith("ru");
    }
}
