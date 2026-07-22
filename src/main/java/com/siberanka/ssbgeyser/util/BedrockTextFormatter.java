package com.siberanka.ssbgeyser.util;

public final class BedrockTextFormatter {

    private static final char COLOR_MARKER = '\u00A7';

    private BedrockTextFormatter() {
    }

    public static String formatButtonLine(String input, int maxLength, char defaultColor, boolean remapLowContrast) {
        if (input == null || maxLength <= 0 || plainText(input, Integer.MAX_VALUE, false).isBlank()) {
            return "";
        }

        StringBuilder output = new StringBuilder(Math.min(input.length() + 2, maxLength));
        appendCode(output, defaultColor, maxLength);

        for (int index = 0; index < input.length() && output.length() < maxLength; index++) {
            char current = input.charAt(index);
            if (current == COLOR_MARKER && index + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(index + 1));
                int hexEnd = findHexColorEnd(input, index);
                if (hexEnd >= 0) {
                    appendCode(output, defaultColor, maxLength);
                    index = hexEnd - 1;
                } else if (isLegacyColor(code)) {
                    appendCode(output, remapLowContrast && (code == '7' || code == 'f') ? defaultColor : code, maxLength);
                    index++;
                } else if (code == 'r') {
                    appendCode(output, defaultColor, maxLength);
                    index++;
                } else if (code == 'l' || code == 'o') {
                    appendCode(output, code, maxLength);
                    index++;
                } else if (isLegacyFormat(code)) {
                    index++;
                }
                continue;
            }

            if (current >= 32 && current != 127 && current != COLOR_MARKER) {
                output.append(current);
            }
        }

        return plainText(output.toString(), Integer.MAX_VALUE, false).isBlank() ? "" : output.toString();
    }

    public static String plainText(String input, int maxLength, boolean allowNewLines) {
        if (input == null || maxLength <= 0) {
            return "";
        }

        StringBuilder output = new StringBuilder(Math.min(input.length(), maxLength));
        for (int index = 0; index < input.length() && output.length() < maxLength; index++) {
            char current = input.charAt(index);
            if (current == COLOR_MARKER && index + 1 < input.length()) {
                int hexEnd = findHexColorEnd(input, index);
                if (hexEnd >= 0) {
                    index = hexEnd - 1;
                } else if (isLegacyCode(input.charAt(index + 1))) {
                    index++;
                }
                continue;
            }

            if (current == '\n' && allowNewLines) {
                output.append(current);
            } else if (current >= 32 && current != 127 && current != COLOR_MARKER) {
                output.append(current);
            }
        }

        return output.toString();
    }

    public static boolean hasVisibleText(String input) {
        return !plainText(input, Integer.MAX_VALUE, true).isBlank();
    }

    private static void appendCode(StringBuilder output, char code, int maxLength) {
        char normalized = Character.toLowerCase(code);
        if (output.length() >= 2
                && output.charAt(output.length() - 2) == COLOR_MARKER
                && output.charAt(output.length() - 1) == normalized) {
            return;
        }

        if (output.length() + 2 <= maxLength) {
            output.append(COLOR_MARKER).append(normalized);
        }
    }

    private static boolean isLegacyCode(char code) {
        char normalized = Character.toLowerCase(code);
        return isLegacyColor(normalized) || isLegacyFormat(normalized) || normalized == 'r' || normalized == 'x';
    }

    private static boolean isLegacyColor(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private static boolean isLegacyFormat(char code) {
        return code >= 'k' && code <= 'o';
    }

    private static int findHexColorEnd(String input, int markerIndex) {
        if (markerIndex + 13 >= input.length() || Character.toLowerCase(input.charAt(markerIndex + 1)) != 'x') {
            return -1;
        }

        for (int offset = 2; offset < 14; offset += 2) {
            if (input.charAt(markerIndex + offset) != COLOR_MARKER
                    || Character.digit(input.charAt(markerIndex + offset + 1), 16) < 0) {
                return -1;
            }
        }

        return markerIndex + 14;
    }
}
