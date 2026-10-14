package com.example.lira.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceVoiceParserTest {
    @Test
    void parsesMultipleOperationsAndRussianThousands() {
        var entries = FinanceVoiceParser.parse(
                "потратил тысячу рублей на продукты, заработал две тысячи рублей зарплата");

        assertEquals(2, entries.size());
        assertFalse(entries.get(0).income());
        assertEquals(100_000, entries.get(0).amountCents());
        assertEquals("Покупки", entries.get(0).category());
        assertTrue(entries.get(1).income());
        assertEquals(200_000, entries.get(1).amountCents());
        assertEquals("Зарплата", entries.get(1).category());
    }

    @Test
    void parsesDecimalAmounts() {
        var entries = FinanceVoiceParser.parse("потратил 125,50 рублей на кофе");

        assertEquals(1, entries.size());
        assertEquals(12_550, entries.get(0).amountCents());
        assertEquals("Кафе и еда", entries.get(0).category());
    }
}
