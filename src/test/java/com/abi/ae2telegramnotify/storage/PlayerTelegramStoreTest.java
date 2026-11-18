package com.abi.ae2telegramnotify.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTelegramStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void bindSaveReloadAndResolve() {
        Path file = tempDir.resolve("players.json");
        UUID uuid = UUID.randomUUID();

        PlayerTelegramStore store = new PlayerTelegramStore(file);
        store.bind(uuid, "Steve", "123456789");
        assertEquals(Optional.of("123456789"), store.resolveChatId(uuid, "999999"));

        // Reload from disk
        PlayerTelegramStore reloaded = new PlayerTelegramStore(file);
        reloaded.load();
        assertEquals(Optional.of("123456789"), reloaded.resolveChatId(uuid, "999999"));
        assertNotNull(reloaded.getBinding(uuid));
        assertEquals("Steve", reloaded.getBinding(uuid).lastKnownName);

        // Toggle pauses notifications even when fallback exists
        assertFalse(reloaded.toggleEnabled(uuid));
        assertEquals(Optional.empty(), reloaded.resolveChatId(uuid, "999999"));

        // Toggle back resumes
        assertTrue(reloaded.toggleEnabled(uuid));
        assertEquals(Optional.of("123456789"), reloaded.resolveChatId(uuid, "999999"));

        // Unbind falls back to defaultChatId if provided
        assertTrue(reloaded.unbind(uuid));
        assertEquals(Optional.of("999999"), reloaded.resolveChatId(uuid, "999999"));
        assertEquals(Optional.empty(), reloaded.resolveChatId(uuid, ""));
    }

    @Test
    void linkCodeGenerationAndValidation() {
        Path file = tempDir.resolve("players.json");
        UUID uuid = UUID.randomUUID();

        PlayerTelegramStore store = new PlayerTelegramStore(file);
        String code = store.createLinkCode(uuid);
        assertTrue(code.startsWith("AE-"));
        assertEquals(9, code.length());

        PlayerTelegramStore.PendingLink pending = store.getValidLinkCode(uuid);
        assertNotNull(pending);
        assertEquals(code, pending.code());

        // Binding clears pending link code
        store.bind(uuid, "Alex", "-100555444");
        assertNull(store.getValidLinkCode(uuid));
    }
}
