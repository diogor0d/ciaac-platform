package com.ciaac.minecraft.minigames.updater;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON parser sufficient for untrusted GitHub API responses. */
final class StrictJson {
    private static final int MAX_DEPTH = 32;
    private final String input;
    private int index;

    private StrictJson(String input) {
        if (input == null || input.length() > 2_000_000) throw new IllegalArgumentException("JSON is too large");
        this.input = input;
    }

    static Object parse(String input) {
        StrictJson parser = new StrictJson(input);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != parser.input.length()) throw parser.invalid();
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw invalid();
        whitespace();
        if (index >= input.length()) throw invalid();
        return switch (input.charAt(index)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        index++;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        whitespace();
        if (take('}')) return Map.copyOf(result);
        while (true) {
            whitespace();
            if (index >= input.length() || input.charAt(index) != '"') throw invalid();
            String key = string();
            whitespace();
            if (!take(':')) throw invalid();
            if (result.containsKey(key)) throw invalid();
            result.put(key, value(depth));
            whitespace();
            if (take('}')) return Map.copyOf(result);
            if (!take(',')) throw invalid();
        }
    }

    private List<Object> array(int depth) {
        index++;
        ArrayList<Object> result = new ArrayList<>();
        whitespace();
        if (take(']')) return List.copyOf(result);
        while (true) {
            if (result.size() >= 10_000) throw invalid();
            result.add(value(depth));
            whitespace();
            if (take(']')) return List.copyOf(result);
            if (!take(',')) throw invalid();
        }
    }

    private String string() {
        index++;
        StringBuilder value = new StringBuilder();
        while (index < input.length()) {
            char current = input.charAt(index++);
            if (current == '"') return value.toString();
            if (current < 0x20) throw invalid();
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (index >= input.length()) throw invalid();
            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(unicode());
                default -> throw invalid();
            }
            if (value.length() > 1_000_000) throw invalid();
        }
        throw invalid();
    }

    private char unicode() {
        if (index + 4 > input.length()) throw invalid();
        try {
            char result = (char) Integer.parseInt(input.substring(index, index + 4), 16);
            index += 4;
            return result;
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private Number number() {
        int start = index;
        if (take('-') && index >= input.length()) throw invalid();
        if (take('0')) {
            if (index < input.length() && Character.isDigit(input.charAt(index))) throw invalid();
        } else {
            digits();
        }
        boolean decimal = false;
        if (take('.')) {
            decimal = true;
            digits();
        }
        if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
            decimal = true;
            index++;
            if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
            digits();
        }
        try {
            String raw = input.substring(start, index);
            return decimal ? Double.parseDouble(raw) : Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private void digits() {
        int start = index;
        while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
        if (start == index) throw invalid();
    }

    private Object literal(String expected, Object value) {
        if (!input.startsWith(expected, index)) throw invalid();
        index += expected.length();
        return value;
    }

    private boolean take(char value) {
        if (index < input.length() && input.charAt(index) == value) {
            index++;
            return true;
        }
        return false;
    }

    private void whitespace() {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid bounded JSON at offset " + index);
    }
}
