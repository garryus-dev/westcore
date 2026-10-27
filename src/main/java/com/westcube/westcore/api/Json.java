package com.westcube.westcore.api;

final class Json {

    private Json() {}

    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }
}
