package com.abi.ae2telegramnotify.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class TelegramClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramClient.class);
    private static final long MAX_LINK_MESSAGE_AGE_SECONDS = 600;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static volatile ExecutorService executor = createExecutor();
    private static volatile HttpClient httpClient = createHttpClient(executor);

    private TelegramClient() {
    }

    private static ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread t = new Thread(runnable, "ae2-telegram-notify-" + THREAD_COUNTER.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    private static HttpClient createHttpClient(ExecutorService exec) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(exec)
                .build();
    }

    private static synchronized void ensureInitialized() {
        if (executor == null || executor.isShutdown()) {
            executor = createExecutor();
            httpClient = createHttpClient(executor);
        }
    }

    public static synchronized void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    public static CompletableFuture<SendResult> sendMessageAsync(
            String apiBaseUrl,
            String botToken,
            String chatId,
            String htmlMessage,
            int timeoutSeconds
    ) {
        if (botToken == null || botToken.isBlank()) {
            return CompletableFuture.completedFuture(SendResult.failure(0, "Telegram botToken is not configured"));
        }
        if (chatId == null || chatId.isBlank()) {
            return CompletableFuture.completedFuture(SendResult.failure(0, "Telegram chatId is empty"));
        }

        ensureInitialized();

        String normalizedBase = normalizeBaseUrl(apiBaseUrl);
        URI uri = URI.create(normalizedBase + "/bot" + botToken.trim() + "/sendMessage");

        JsonObject payload = new JsonObject();
        payload.addProperty("chat_id", chatId.trim());
        payload.addProperty("text", htmlMessage);
        payload.addProperty("parse_mode", "HTML");
        payload.addProperty("disable_web_page_preview", true);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parseSendResponse(response.statusCode(), response.body()))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    LOGGER.warn("Failed to send Telegram message: {}", cause.getMessage());
                    return SendResult.failure(0, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                });
    }

    public static CompletableFuture<PollResult> findChatByLinkCodeAsync(
            String apiBaseUrl,
            String botToken,
            String expectedCode,
            int timeoutSeconds
    ) {
        if (botToken == null || botToken.isBlank()) {
            return CompletableFuture.completedFuture(PollResult.error("Telegram botToken is not configured"));
        }
        if (expectedCode == null || expectedCode.isBlank()) {
            return CompletableFuture.completedFuture(PollResult.error("Expected link code is empty"));
        }

        ensureInitialized();

        String normalizedBase = normalizeBaseUrl(apiBaseUrl);
        URI uri = URI.create(normalizedBase + "/bot" + botToken.trim()
                + "/getUpdates?limit=100&allowed_updates=%5B%22message%22%5D");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parsePollResponse(response.statusCode(), response.body(), expectedCode))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    LOGGER.warn("Failed to poll Telegram getUpdates: {}", cause.getMessage());
                    return PollResult.error(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                });
    }

    private static SendResult parseSendResponse(int statusCode, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
            if (ok) {
                return SendResult.success(statusCode);
            }
            String desc = json.has("description") ? json.get("description").getAsString() : "Unknown Telegram API error";
            return SendResult.failure(statusCode, desc);
        } catch (Exception e) {
            if (statusCode >= 200 && statusCode < 300) {
                return SendResult.success(statusCode);
            }
            return SendResult.failure(statusCode, "HTTP " + statusCode + ": " + body);
        }
    }

    private static PollResult parsePollResponse(int statusCode, String body, String expectedCode) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                String desc = json.has("description") ? json.get("description").getAsString() : ("HTTP " + statusCode);
                return PollResult.error(desc);
            }
            JsonArray results = json.has("result") && json.get("result").isJsonArray()
                    ? json.getAsJsonArray("result")
                    : new JsonArray();

            long minTimestamp = Instant.now().getEpochSecond() - MAX_LINK_MESSAGE_AGE_SECONDS;
            String target = expectedCode.trim().toUpperCase(Locale.ROOT);

            for (int i = results.size() - 1; i >= 0; i--) {
                JsonObject update = results.get(i).getAsJsonObject();
                if (!update.has("message") || !update.get("message").isJsonObject()) {
                    continue;
                }
                JsonObject message = update.getAsJsonObject("message");
                long msgDate = message.has("date") ? message.get("date").getAsLong() : 0L;
                if (msgDate > 0 && msgDate < minTimestamp) {
                    continue;
                }
                if (!message.has("text") || !message.has("chat")) {
                    continue;
                }
                String text = message.get("text").getAsString().trim().toUpperCase(Locale.ROOT);
                if (matchesLinkCode(text, target)) {
                    JsonObject chat = message.getAsJsonObject("chat");
                    String chatId = chat.get("id").getAsString();
                    String label = extractChatLabel(chat);
                    return PollResult.found(chatId, label);
                }
            }
            return PollResult.notFound();
        } catch (Exception e) {
            return PollResult.error("Failed to parse Telegram response: " + e.getMessage());
        }
    }

    static boolean matchesLinkCode(String upperText, String upperExpectedCode) {
        if (upperText.equals(upperExpectedCode)) {
            return true;
        }
        if (upperText.startsWith("/START ")) {
            String arg = upperText.substring("/START ".length()).trim();
            return arg.equals(upperExpectedCode);
        }
        return false;
    }

    private static String extractChatLabel(JsonObject chat) {
        JsonElement username = chat.get("username");
        if (username != null && !username.isJsonNull() && !username.getAsString().isBlank()) {
            return "@" + username.getAsString();
        }
        JsonElement title = chat.get("title");
        if (title != null && !title.isJsonNull() && !title.getAsString().isBlank()) {
            return title.getAsString();
        }
        JsonElement firstName = chat.get("first_name");
        if (firstName != null && !firstName.isJsonNull() && !firstName.getAsString().isBlank()) {
            return firstName.getAsString();
        }
        return "Telegram user";
    }

    static String normalizeBaseUrl(@Nullable String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return "https://api.telegram.org";
        }
        String trimmed = apiBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record SendResult(boolean ok, int statusCode, @Nullable String error) {
        public static SendResult success(int statusCode) {
            return new SendResult(true, statusCode, null);
        }

        public static SendResult failure(int statusCode, String error) {
            return new SendResult(false, statusCode, error);
        }
    }

    public record PollResult(boolean found, @Nullable String chatId, @Nullable String telegramLabel, @Nullable String error) {
        public static PollResult found(String chatId, String telegramLabel) {
            return new PollResult(true, chatId, telegramLabel, null);
        }

        public static PollResult notFound() {
            return new PollResult(false, null, null, null);
        }

        public static PollResult error(String error) {
            return new PollResult(false, null, null, error);
        }
    }
}
