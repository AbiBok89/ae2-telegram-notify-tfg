package com.abi.ae2telegramnotify.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMessageFormatterTest {

    @Test
    void formatsRussianMessageWithHtmlEscaping() {
        String msg = TelegramMessageFormatter.formatCompletionMessage(
                "ru",
                "<Quantum & Core>",
                "64",
                "Player<1>",
                "CPU #1 & Main",
                134_000_000_000L, // 2m 14s
                "minecraft:overworld",
                true,
                true,
                false
        );

        assertTrue(msg.contains("Заказ AE2 завершён"));
        assertTrue(msg.contains("&lt;Quantum &amp; Core&gt; × 64"));
        assertTrue(msg.contains("Player&lt;1&gt;"));
        assertTrue(msg.contains("CPU #1 &amp; Main"));
        assertTrue(msg.contains("2 мин. 14 сек."));
        assertFalse(msg.contains("minecraft:overworld"));
    }

    @Test
    void formatsEnglishMessageAndDurations() {
        String msg = TelegramMessageFormatter.formatCompletionMessage(
                "en",
                "ME Glass Cable",
                "128",
                "Alex",
                null,
                3_665_000_000_000L, // 1h 1m 5s
                "minecraft:the_end",
                true,
                true,
                true
        );

        assertTrue(msg.contains("AE2 Crafting Order Finished"));
        assertTrue(msg.contains("ME Glass Cable × 128"));
        assertTrue(msg.contains("1h 1m 5s"));
        assertTrue(msg.contains("minecraft:the_end"));
        assertFalse(msg.contains("CPU:"));
    }
}
