package com.abi.ae2telegramnotify.telegram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
}
