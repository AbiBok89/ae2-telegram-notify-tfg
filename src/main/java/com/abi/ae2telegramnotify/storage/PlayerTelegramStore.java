package com.abi.ae2telegramnotify.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTelegramStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerTelegramStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerBinding>>() {}.getType();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long LINK_CODE_TTL_SECONDS = 300;

    private static volatile PlayerTelegramStore instance;

    private final Path storagePath;
    private final Map<UUID, PlayerBinding> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLink> pendingLinks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean dirty = false;

    public PlayerTelegramStore(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static PlayerTelegramStore getInstance() {
        if (instance == null) {
            synchronized (PlayerTelegramStore.class) {
                if (instance == null) {
                    Path path = FMLPaths.CONFIGDIR.get().resolve("ae2telegramnotify-players.json");
                    instance = new PlayerTelegramStore(path);
                }
            }
        }
        return instance;
    }

    public synchronized void load() {
        bindings.clear();
        if (!Files.exists(storagePath)) {
            return;
        }
        try {
            String json = Files.readString(storagePath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, PlayerBinding> loaded = GSON.fromJson(json, MAP_TYPE);
            if (loaded != null) {
                for (var entry : loaded.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        PlayerBinding binding = entry.getValue();
                        if (binding != null && binding.chatId != null && !binding.chatId.isBlank()) {
                            bindings.put(uuid, binding);
                        }
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Skipping invalid player UUID in {}: {}", storagePath, entry.getKey());
                    }
                }
            }
            dirty = false;
            LOGGER.info("Loaded {} Telegram player bindings from {}", bindings.size(), storagePath);
        } catch (Exception e) {
            LOGGER.error("Failed to load Telegram player bindings from {}", storagePath, e);
        }
    }

    public synchronized void save() {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, PlayerBinding> serializable = new LinkedHashMap<>();
            bindings.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> serializable.put(e.getKey().toString(), e.getValue()));
            Files.writeString(storagePath, GSON.toJson(serializable), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            LOGGER.error("Failed to save Telegram player bindings to {}", storagePath, e);
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void bind(UUID playerUuid, String playerName, String chatId) {
        String trimmedChatId = chatId.trim();
        PlayerBinding binding = new PlayerBinding(playerName, trimmedChatId, true, Instant.now().getEpochSecond());
        bindings.put(playerUuid, binding);
        pendingLinks.remove(playerUuid);
        dirty = true;
        save();
    }

    public boolean unbind(UUID playerUuid) {
        PlayerBinding removed = bindings.remove(playerUuid);
        pendingLinks.remove(playerUuid);
        if (removed != null) {
            dirty = true;
            save();
            return true;
        }
        return false;
    }

    public boolean toggleEnabled(UUID playerUuid) {
        PlayerBinding existing = bindings.get(playerUuid);
        if (existing == null) {
            return false;
        }
        existing.enabled = !existing.enabled;
        dirty = true;
        save();
        return existing.enabled;
    }

    public void updateKnownName(UUID playerUuid, String playerName) {
        PlayerBinding existing = bindings.get(playerUuid);
        if (existing != null && playerName != null && !playerName.equals(existing.lastKnownName)) {
            existing.lastKnownName = playerName;
            dirty = true;
        }
    }

    @Nullable
    public PlayerBinding getBinding(UUID playerUuid) {
        return bindings.get(playerUuid);
    }

    public Map<UUID, PlayerBinding> getAllBindings() {
        return Collections.unmodifiableMap(bindings);
    }

    /**
     * Resolves the target Telegram chat ID for a player.
     * Returns empty if the player explicitly disabled their binding or if neither a personal binding
     * nor a fallback defaultChatId is configured.
     */
    public Optional<String> resolveChatId(UUID playerUuid, @Nullable String fallbackDefaultChatId) {
        PlayerBinding binding = bindings.get(playerUuid);
        if (binding != null) {
            if (!binding.enabled) {
                return Optional.empty();
            }
            if (binding.chatId != null && !binding.chatId.isBlank()) {
                return Optional.of(binding.chatId.trim());
            }
        }
        if (fallbackDefaultChatId != null && !fallbackDefaultChatId.isBlank()) {
            return Optional.of(fallbackDefaultChatId.trim());
        }
        return Optional.empty();
    }

    public String createLinkCode(UUID playerUuid) {
        purgeExpiredCodes();
        StringBuilder sb = new StringBuilder("AE-");
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        String code = sb.toString();
        long now = Instant.now().getEpochSecond();
        pendingLinks.put(playerUuid, new PendingLink(code, now + LINK_CODE_TTL_SECONDS));
        return code;
    }

    @Nullable
    public PendingLink getValidLinkCode(UUID playerUuid) {
        purgeExpiredCodes();
        PendingLink pending = pendingLinks.get(playerUuid);
        if (pending != null && pending.expiresAtEpochSeconds >= Instant.now().getEpochSecond()) {
            return pending;
        }
        return null;
    }

    private void purgeExpiredCodes() {
        long now = Instant.now().getEpochSecond();
        pendingLinks.entrySet().removeIf(e -> e.getValue().expiresAtEpochSeconds < now);
    }

    public static class PlayerBinding {
        public String lastKnownName;
        public String chatId;
        public boolean enabled;
        public long boundAtEpochSeconds;

        public PlayerBinding() {
        }

        public PlayerBinding(String lastKnownName, String chatId, boolean enabled, long boundAtEpochSeconds) {
            this.lastKnownName = lastKnownName;
            this.chatId = chatId;
            this.enabled = enabled;
            this.boundAtEpochSeconds = boundAtEpochSeconds;
        }
    }

    public record PendingLink(String code, long expiresAtEpochSeconds) {
    }
}
