/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.processing.staticcompile;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON the report needs: string literals out, and the report's own records back in.
 */
final class Json {

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static String string(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Parses one JSON value: an object as a {@link Map}, an array as a {@link List}, a string, a
     * number as a {@link Long} or {@link Double}, a boolean, or {@code null}.
     *
     * @param text The JSON
     * @return The value
     * @throws IllegalArgumentException When the text is not JSON
     */
    static @Nullable Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.skipWhitespace();
        if (json.position != text.length()) {
            throw json.error("trailing characters");
        }
        return value;
    }

    private @Nullable Object value() {
        skipWhitespace();
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            Object value = value();
            if (value != null) {
                map.put(key, value);
            }
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char escaped = next();
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("bad escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> out.append(escaped);
                }
            } else {
                out.append(c);
            }
        }
    }

    private Object number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String token = text.substring(start, position);
        if (token.isEmpty()) {
            throw error("unexpected character");
        }
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.valueOf(token);
            }
            return Long.valueOf(token);
        } catch (NumberFormatException e) {
            throw error("bad number " + token);
        }
    }

    private @Nullable Object literal(String token, @Nullable Object value) {
        if (!text.startsWith(token, position)) {
            throw error("expected " + token);
        }
        position += token.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(position);
    }

    private char next() {
        char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw error("expected " + expected);
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at " + position + ": " + what);
    }
}
