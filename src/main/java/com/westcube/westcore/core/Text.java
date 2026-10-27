package com.westcube.westcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage);
    }

    /** Remplace {clé} par sa valeur : fill(s, "player", "Bob", "time", "5sec"). */
    public static String fill(String text, String... replacements) {
        if (text == null) return "";
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return text;
    }

    public static String escape(String text) {
        return MM.escapeTags(text);
    }

    /** Format "1h 5min", "3min 12sec" ou "8sec". */
    public static String duration(long millis) {
        long total = Math.max(0, millis / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return h + "h " + m + "min";
        if (m > 0) return m + "min " + s + "sec";
        return s + "sec";
    }
}
