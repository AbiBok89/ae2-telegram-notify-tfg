package com.abi.ae2telegramnotify.telegram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendMessagePostsExpectedPayloadAndHandlesSuccessAndFailure() {
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.createContext("/botTEST_TOKEN/sendMessage", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            capturedBody.set(body);

            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            String chatId = req.get("chat_id").getAsString();
            String resp;
            int code;
            if ("12345".equals(chatId)) {
                code = 200;
                resp = "{\"ok\":true,\"result\":{\"message_id\":1}}";
            } else {
                code = 400;
                resp = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}";
            }

            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        TelegramClient.SendResult ok = TelegramClient
                .sendMessageAsync(baseUrl, "TEST_TOKEN", "12345", "<b>Done!</b>", 5)
                .join();
        assertTrue(ok.ok());
        assertEquals(200, ok.statusCode());
        JsonObject parsed = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        assertEquals("12345", parsed.get("chat_id").getAsString());
        assertEquals("<b>Done!</b>", parsed.get("text").getAsString());
        assertEquals("HTML", parsed.get("parse_mode").getAsString());

        TelegramClient.SendResult err = TelegramClient
                .sendMessageAsync(baseUrl, "TEST_TOKEN", "99999", "<b>Done!</b>", 5)
                .join();
        assertFalse(err.ok());
        assertEquals("Bad Request: chat not found", err.error());
    }

    @Test
    void findChatByLinkCodeMatchesStartCommandAndIgnoresStaleMessages() {
        long now = Instant.now().getEpochSecond();
        long stale = now - 3600;

        server.createContext("/botTEST_TOKEN/getUpdates", exchange -> {
            String resp = """
                    {
                      "ok": true,
                      "result": [
                        {
                          "update_id": 1,
                          "message": {
                            "date": %d,
                            "text": "AE-XYZ123",
                            "chat": { "id": 11111, "username": "old_user" }
                          }
                        },
                        {
                          "update_id": 2,
                          "message": {
                            "date": %d,
                            "text": "/start ae-xyz123",
                            "chat": { "id": 777888, "username": "abi_player" }
                          }
                        }
                      ]
                    }
                    """.formatted(stale, now);

            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        TelegramClient.PollResult found = TelegramClient
                .findChatByLinkCodeAsync(baseUrl, "TEST_TOKEN", "AE-XYZ123", 5)
                .join();
        assertTrue(found.found());
        assertEquals("777888", found.chatId());
        assertEquals("@abi_player", found.telegramLabel());

        TelegramClient.PollResult missing = TelegramClient
                .findChatByLinkCodeAsync(baseUrl, "TEST_TOKEN", "AE-NOTFOUND", 5)
                .join();
        assertFalse(missing.found());
        assertNull(missing.error());
    }

    @Test
    void sendMessageViaSocks5ProxyWithAuthAndRemoteDns() throws Exception {
        AtomicReference<String> capturedTargetHost = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.createContext("/botPROXY_TOKEN/sendMessage", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.set(body);
            byte[] out = "{\"ok\":true,\"result\":{\"message_id\":42}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        ExecutorService relayPool = Executors.newCachedThreadPool();
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int socksPort = socksServer.getLocalPort();
            int httpPort = server.getAddress().getPort();

            relayPool.submit(() -> handleSingleSocks5Client(
                    socksServer, "user1", "pass2", httpPort, capturedTargetHost, relayPool));

            TelegramClient.ProxyConfig proxyConfig = new TelegramClient.ProxyConfig(
                    TelegramClient.ProxyType.SOCKS5, "127.0.0.1", socksPort, "user1", "pass2");

            String remoteOnlyBaseUrl = "http://remote-only-telegram.invalid:" + httpPort;
            TelegramClient.SendResult res = TelegramClient
                    .sendMessageAsync(remoteOnlyBaseUrl, "PROXY_TOKEN", "555", "<b>Via SOCKS5</b>", 5, proxyConfig)
                    .join();

            assertTrue(res.ok(), "Expected success via SOCKS5, got error: " + res.error());
            assertEquals("remote-only-telegram.invalid", capturedTargetHost.get());
            JsonObject parsed = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
            assertEquals("555", parsed.get("chat_id").getAsString());
            assertEquals("<b>Via SOCKS5</b>", parsed.get("text").getAsString());
        } finally {
            relayPool.shutdownNow();
        }
    }

    @Test
    void sendMessageViaSocks5ReportsRejectedCredentials() throws Exception {
        ExecutorService relayPool = Executors.newCachedThreadPool();
        try (ServerSocket socksServer = new ServerSocket(0)) {
            int socksPort = socksServer.getLocalPort();
            int httpPort = server.getAddress().getPort();

            relayPool.submit(() -> handleSingleSocks5Client(
                    socksServer, "expectedUser", "expectedPass", httpPort, new AtomicReference<>(), relayPool));

            TelegramClient.ProxyConfig badProxy = new TelegramClient.ProxyConfig(
                    TelegramClient.ProxyType.SOCKS5, "127.0.0.1", socksPort, "wrongUser", "wrongPass");

            TelegramClient.SendResult res = TelegramClient
                    .sendMessageAsync(baseUrl, "PROXY_TOKEN", "555", "<b>Fail</b>", 5, badProxy)
                    .join();

            assertFalse(res.ok());
            assertNotNull(res.error());
            assertTrue(res.error().contains("authentication rejected"), "Unexpected error: " + res.error());
        } finally {
            relayPool.shutdownNow();
        }
    }

    private static void handleSingleSocks5Client(
            ServerSocket socksServer,
            String expectedUser,
            String expectedPass,
            int forwardPort,
            AtomicReference<String> capturedHost,
            ExecutorService pool
    ) {
        try (Socket client = socksServer.accept()) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            int ver = in.read();
            int nMethods = in.read();
            if (ver != 0x05 || nMethods <= 0) {
                return;
            }
            in.readNBytes(nMethods);
            // Require username/password auth (0x02)
            out.write(new byte[]{0x05, 0x02});
            out.flush();

            int authVer = in.read();
            int uLen = in.read();
            String user = new String(in.readNBytes(uLen), StandardCharsets.UTF_8);
            int pLen = in.read();
            String pass = new String(in.readNBytes(pLen), StandardCharsets.UTF_8);
            if (authVer != 0x01 || !expectedUser.equals(user) || !expectedPass.equals(pass)) {
                out.write(new byte[]{0x01, 0x01});
                out.flush();
                return;
            }
            out.write(new byte[]{0x01, 0x00});
            out.flush();

            byte[] reqHead = in.readNBytes(4);
            if (reqHead.length < 4 || reqHead[1] != 0x01 || reqHead[3] != 0x03) {
                return;
            }
            int hostLen = in.read();
            String targetHost = new String(in.readNBytes(hostLen), StandardCharsets.UTF_8);
            in.readNBytes(2); // target port
            capturedHost.set(targetHost);

            try (Socket upstream = new Socket("127.0.0.1", forwardPort)) {
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
                out.flush();

                pool.submit(() -> copyQuietly(in, upstream));
                copyQuietly(upstream.getInputStream(), client);
            }
        } catch (IOException ignored) {
        }
    }

    private static void copyQuietly(InputStream in, Socket target) {
        try {
            in.transferTo(target.getOutputStream());
            target.shutdownOutput();
        } catch (IOException ignored) {
        }
    }
}
