package com.abi.ae2telegramnotify.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
        return sendMessageAsync(apiBaseUrl, botToken, chatId, htmlMessage, timeoutSeconds, ProxyConfig.NONE);
    }

    public static CompletableFuture<SendResult> sendMessageAsync(
            String apiBaseUrl,
            String botToken,
            String chatId,
            String htmlMessage,
            int timeoutSeconds,
            @Nullable ProxyConfig proxyConfig
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
        String jsonBody = payload.toString();
        int clampedTimeout = Math.max(2, timeoutSeconds);

        ProxyConfig effectiveProxy = proxyConfig != null ? proxyConfig : ProxyConfig.NONE;
        if (effectiveProxy.isEnabled()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    RawHttpResponse resp = executeViaProxy(uri, "POST", jsonBody, clampedTimeout, effectiveProxy);
                    return parseSendResponse(resp.statusCode(), resp.body());
                } catch (Exception ex) {
                    LOGGER.warn("Failed to send Telegram message via proxy {}: {}", effectiveProxy.describe(), ex.getMessage());
                    return SendResult.failure(0, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
            }, executor);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(clampedTimeout))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
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
        return findChatByLinkCodeAsync(apiBaseUrl, botToken, expectedCode, timeoutSeconds, ProxyConfig.NONE);
    }

    public static CompletableFuture<PollResult> findChatByLinkCodeAsync(
            String apiBaseUrl,
            String botToken,
            String expectedCode,
            int timeoutSeconds,
            @Nullable ProxyConfig proxyConfig
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
        int clampedTimeout = Math.max(2, timeoutSeconds);

        ProxyConfig effectiveProxy = proxyConfig != null ? proxyConfig : ProxyConfig.NONE;
        if (effectiveProxy.isEnabled()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    RawHttpResponse resp = executeViaProxy(uri, "GET", null, clampedTimeout, effectiveProxy);
                    return parsePollResponse(resp.statusCode(), resp.body(), expectedCode);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to poll Telegram getUpdates via proxy {}: {}", effectiveProxy.describe(), ex.getMessage());
                    return PollResult.error(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
            }, executor);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(clampedTimeout))
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

    private static RawHttpResponse executeViaProxy(
            URI uri,
            String method,
            @Nullable String jsonBody,
            int timeoutSeconds,
            ProxyConfig proxy
    ) throws IOException {
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
        boolean isHttps = "https".equals(scheme);
        String targetHost = uri.getHost();
        if (targetHost == null || targetHost.isBlank()) {
            throw new IOException("Invalid URI host: " + uri);
        }
        int targetPort = uri.getPort() > 0 ? uri.getPort() : (isHttps ? 443 : 80);
        int timeoutMs = Math.max(2000, timeoutSeconds * 1000);

        try (Socket rawSocket = new Socket()) {
            rawSocket.connect(new InetSocketAddress(proxy.host().trim(), proxy.port()), timeoutMs);
            rawSocket.setSoTimeout(timeoutMs);

            boolean sendAbsoluteForm = false;
            if (proxy.type() == ProxyType.SOCKS5) {
                performSocks5Handshake(rawSocket, targetHost, targetPort, proxy.username(), proxy.password());
            } else if (proxy.type() == ProxyType.HTTP) {
                if (isHttps) {
                    performHttpConnectHandshake(rawSocket, targetHost, targetPort, proxy.username(), proxy.password());
                } else {
                    sendAbsoluteForm = true;
                }
            } else {
                throw new IOException("Unsupported proxy type: " + proxy.type());
            }

            Socket activeSocket = rawSocket;
            if (isHttps) {
                SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(rawSocket, targetHost, targetPort, true);
                SSLParameters params = sslSocket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(params);
                sslSocket.startHandshake();
                activeSocket = sslSocket;
            }

            try (Socket closeable = activeSocket) {
                OutputStream out = closeable.getOutputStream();
                InputStream in = closeable.getInputStream();

                String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                    path = path + "?" + uri.getRawQuery();
                }
                String requestTarget = sendAbsoluteForm ? uri.toString() : path;
                String hostHeader = uri.getPort() > 0 ? (targetHost + ":" + targetPort) : targetHost;

                byte[] bodyBytes = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0];

                StringBuilder reqHeaders = new StringBuilder();
                reqHeaders.append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n");
                reqHeaders.append("Host: ").append(hostHeader).append("\r\n");
                reqHeaders.append("Connection: close\r\n");
                reqHeaders.append("Accept-Encoding: identity\r\n");
                if (sendAbsoluteForm && proxy.hasCredentials()) {
                    reqHeaders.append("Proxy-Authorization: ").append(basicProxyAuthHeader(proxy)).append("\r\n");
                }
                if (jsonBody != null) {
                    reqHeaders.append("Content-Type: application/json; charset=UTF-8\r\n");
                    reqHeaders.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
                }
                reqHeaders.append("\r\n");

                out.write(reqHeaders.toString().getBytes(StandardCharsets.US_ASCII));
                if (bodyBytes.length > 0) {
                    out.write(bodyBytes);
                }
                out.flush();

                return readHttpResponse(in);
            }
        }
    }

    private static void performSocks5Handshake(
            Socket socket,
            String targetHost,
            int targetPort,
            @Nullable String username,
            @Nullable String password
    ) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        boolean hasUser = username != null && !username.isEmpty();
        if (hasUser) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] methodReply = readExact(in, 2);
        if (methodReply[0] != 0x05) {
            throw new IOException("Invalid SOCKS version from proxy: " + (methodReply[0] & 0xFF));
        }

        int chosenMethod = methodReply[1] & 0xFF;
        if (chosenMethod == 0x02) {
            if (!hasUser) {
                throw new IOException("SOCKS5 proxy requires username/password authentication");
            }
            byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);
            if (userBytes.length > 255 || passBytes.length > 255) {
                throw new IOException("SOCKS5 username or password exceeds 255 bytes");
            }
            byte[] authReq = new byte[3 + userBytes.length + passBytes.length];
            authReq[0] = 0x01;
            authReq[1] = (byte) userBytes.length;
            System.arraycopy(userBytes, 0, authReq, 2, userBytes.length);
            authReq[2 + userBytes.length] = (byte) passBytes.length;
            System.arraycopy(passBytes, 0, authReq, 3 + userBytes.length, passBytes.length);
            out.write(authReq);
            out.flush();

            byte[] authReply = readExact(in, 2);
            if (authReply[1] != 0x00) {
                throw new IOException("SOCKS5 authentication rejected by proxy (status=" + (authReply[1] & 0xFF) + ")");
            }
        } else if (chosenMethod != 0x00) {
            throw new IOException("SOCKS5 proxy rejected authentication method (method=" + chosenMethod + ")");
        }

        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new IOException("Target hostname exceeds 255 bytes for SOCKS5");
        }

        byte[] connectReq = new byte[7 + hostBytes.length];
        connectReq[0] = 0x05;
        connectReq[1] = 0x01; // CONNECT
        connectReq[2] = 0x00;
        connectReq[3] = 0x03; // DOMAINNAME (remote DNS resolution on proxy)
        connectReq[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.length);
        connectReq[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
        connectReq[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
        out.write(connectReq);
        out.flush();

        byte[] head = readExact(in, 4);
        if (head[0] != 0x05) {
            throw new IOException("Invalid SOCKS5 CONNECT reply version: " + (head[0] & 0xFF));
        }
        int rep = head[1] & 0xFF;
        if (rep != 0x00) {
            throw new IOException("SOCKS5 CONNECT failed: " + describeSocks5Reply(rep));
        }

        int atyp = head[3] & 0xFF;
        switch (atyp) {
            case 0x01 -> readExact(in, 6);
            case 0x04 -> readExact(in, 18);
            case 0x03 -> {
                int len = readExact(in, 1)[0] & 0xFF;
                readExact(in, len + 2);
            }
            default -> throw new IOException("Unknown SOCKS5 address type in reply: " + atyp);
        }
    }

    private static void performHttpConnectHandshake(
            Socket socket,
            String targetHost,
            int targetPort,
            @Nullable String username,
            @Nullable String password
    ) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        StringBuilder connect = new StringBuilder();
        connect.append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n");
        connect.append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n");
        if (username != null && !username.isEmpty()) {
            String raw = username + ":" + (password != null ? password : "");
            String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            connect.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        connect.append("\r\n");
        out.write(connect.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String headerBlock = readHeaderBlock(in);
        String statusLine = headerBlock.split("\r\n", 2)[0];
        int statusCode = parseStatusCode(statusLine);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP proxy CONNECT failed: " + statusLine);
        }
    }

    private static String basicProxyAuthHeader(ProxyConfig proxy) {
        String user = proxy.username() != null ? proxy.username() : "";
        String pass = proxy.password() != null ? proxy.password() : "";
        String encoded = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static RawHttpResponse readHttpResponse(InputStream in) throws IOException {
        String headerBlock = readHeaderBlock(in);
        String[] lines = headerBlock.split("\r\n");
        int statusCode = lines.length > 0 ? parseStatusCode(lines[0]) : 0;

        long contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            } else if ("transfer-encoding".equals(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }

        byte[] bodyBytes;
        if (chunked) {
            bodyBytes = readChunkedBody(in);
        } else if (contentLength >= 0) {
            bodyBytes = readExact(in, (int) Math.min(contentLength, 16 * 1024 * 1024));
        } else {
            bodyBytes = in.readAllBytes();
        }
        return new RawHttpResponse(statusCode, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Unexpected EOF while reading HTTP response headers");
            }
            buf.write(b);
            if (state == 0 && b == '\r') {
                state = 1;
            } else if (state == 1 && b == '\n') {
                state = 2;
            } else if (state == 2 && b == '\r') {
                state = 3;
            } else if (state == 3 && b == '\n') {
                break;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
            if (buf.size() > 65536) {
                throw new IOException("HTTP response headers exceed 64 KB");
            }
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLineCrLf(in);
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int chunkSize = Integer.parseInt(hex, 16);
            if (chunkSize == 0) {
                while (!readLineCrLf(in).isEmpty()) {
                    // drain optional trailer headers
                }
                break;
            }
            out.write(readExact(in, chunkSize));
            readLineCrLf(in);
        }
        return out.toByteArray();
    }

    private static String readLineCrLf(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Unexpected EOF while reading chunked HTTP response");
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int len = (prev == '\r' && bytes.length > 0) ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, len, StandardCharsets.US_ASCII);
            }
            line.write(b);
            prev = b;
        }
    }

    private static int parseStatusCode(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF (expected " + length + " bytes, got " + offset + ")");
            }
            offset += read;
        }
        return buf;
    }

    private static String describeSocks5Reply(int code) {
        return switch (code) {
            case 1 -> "general SOCKS server failure (1)";
            case 2 -> "connection not allowed by ruleset (2)";
            case 3 -> "network unreachable (3)";
            case 4 -> "host unreachable (4)";
            case 5 -> "connection refused (5)";
            case 6 -> "TTL expired (6)";
            case 7 -> "command not supported (7)";
            case 8 -> "address type not supported (8)";
            default -> "reply code " + code;
        };
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

    public enum ProxyType {
        NONE,
        SOCKS5,
        HTTP;

        public static ProxyType parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            if ("SOCKS5".equals(norm) || "SOCKS5H".equals(norm) || "SOCKS".equals(norm)) {
                return SOCKS5;
            }
            if ("HTTP".equals(norm) || "HTTPS".equals(norm)) {
                return HTTP;
            }
            return NONE;
        }
    }

    public record ProxyConfig(
            ProxyType type,
            String host,
            int port,
            @Nullable String username,
            @Nullable String password
    ) {
        public static final ProxyConfig NONE = new ProxyConfig(ProxyType.NONE, "", 1080, "", "");

        public boolean isEnabled() {
            return type != null
                    && type != ProxyType.NONE
                    && host != null
                    && !host.isBlank()
                    && port > 0
                    && port <= 65535;
        }

        public boolean hasCredentials() {
            return username != null && !username.isEmpty();
        }

        public String describe() {
            if (!isEnabled()) {
                return "NONE";
            }
            return type.name() + " (" + host.trim() + ":" + port + (hasCredentials() ? ", auth" : "") + ")";
        }
    }

    private record RawHttpResponse(int statusCode, String body) {
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
