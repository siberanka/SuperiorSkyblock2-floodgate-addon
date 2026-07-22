package com.siberanka.ssbgeyser.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTextFormatterTest {

    @Test
    void remapsWhiteGrayAndResetToConfiguredContrastColor() {
        String formatted = BedrockTextFormatter.formatButtonLine(
                "\u00A7fWhite \u00A77Gray \u00A7rReset", 128, '8', true);

        assertEquals("\u00A78White \u00A78Gray \u00A78Reset", formatted);
    }

    @Test
    void preservesSemanticColorsAndSafeEmphasis() {
        String formatted = BedrockTextFormatter.formatButtonLine(
                "\u00A7aEnabled \u00A7cDisabled \u00A7lImportant", 128, '8', true);

        assertTrue(formatted.contains("\u00A7aEnabled"));
        assertTrue(formatted.contains("\u00A7cDisabled"));
        assertTrue(formatted.contains("\u00A7lImportant"));
    }

    @Test
    void removesUnsupportedFormattingControlCharactersAndJavaHexCodes() {
        String formatted = BedrockTextFormatter.formatButtonLine(
                "\u00A7kHidden\u0000 \u00A7x\u00A7f\u00A7f\u00A7f\u00A7f\u00A7f\u00A7fVisible", 128, '8', true);

        assertFalse(formatted.contains("\u00A7k"));
        assertFalse(formatted.contains("\u0000"));
        assertFalse(formatted.contains("\u00A7x"));
        assertTrue(formatted.endsWith("Visible"));
    }

    @Test
    void detectsColorOnlyAndWhitespaceOnlyLinesAsEmpty() {
        assertFalse(BedrockTextFormatter.hasVisibleText("\u00A7f   \u00A7r"));
        assertEquals("", BedrockTextFormatter.formatButtonLine("\u00A7f ", 64, '8', true));
    }

    @Test
    void neverExceedsTheRequestedLengthOrEndsWithAColorMarker() {
        String formatted = BedrockTextFormatter.formatButtonLine("0123456789", 7, '8', true);

        assertTrue(formatted.length() <= 7);
        assertFalse(formatted.endsWith("\u00A7"));
        assertEquals("01234", BedrockTextFormatter.plainText(formatted, 64, false));
    }
}
