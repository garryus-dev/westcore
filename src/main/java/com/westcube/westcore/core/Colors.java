package com.westcube.westcore.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textes mélangeant codes legacy (&a, &l, &#ffd027, &x&f&f...) et MiniMessage,
 * comme dans les configs TAB et les préfixes LuckPerms.
 */
public final class Colors {

    private static final Pattern TAB_GRADIENT = Pattern.compile("<#([0-9a-fA-F]{6})>(.*?)</#([0-9a-fA-F]{6})>");
    private static final Pattern SPIGOT_HEX = Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
    private static final Pattern HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");
    private static final Pattern LAST_COLOR = Pattern.compile("(?:[&§]#([0-9a-fA-F]{6})|[&§]([0-9a-fA-F]))");

    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obf"), Map.entry('l', "b"),
            Map.entry('m', "st"), Map.entry('n', "u"), Map.entry('o', "i"), Map.entry('r', "reset"));

    private Colors() {}

    public static Component parse(String text) {
        return Text.mm(toMiniMessage(text));
    }

    /** Une couleur legacy remet le formatage à zéro, comme dans le jeu. */
    public static String toMiniMessage(String text) {
        if (text == null || text.isEmpty()) return "";
        // Dégradé façon TAB : <#debut>texte</#fin>
        text = TAB_GRADIENT.matcher(text).replaceAll("<gradient:#$1:#$3>$2</gradient>");
        if (text.indexOf('&') < 0 && text.indexOf('§') < 0) return text;

        Matcher m = SPIGOT_HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1).replaceAll("[&§]", "");
            m.appendReplacement(sb, "<reset><#" + hex + ">");
        }
        text = m.appendTail(sb).toString();

        text = HEX.matcher(text).replaceAll("<reset><#$1>");

        m = CODE.matcher(text);
        sb = new StringBuilder();
        while (m.find()) {
            char c = Character.toLowerCase(m.group(1).charAt(0));
            String tag = TAGS.get(c);
            boolean color = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            m.appendReplacement(sb, Matcher.quoteReplacement(color ? "<reset><" + tag + ">" : "<" + tag + ">"));
        }
        return m.appendTail(sb).toString();
    }

    /** Dernière couleur d'un texte legacy (couleur du pseudo après un préfixe d'équipe). */
    public static NamedTextColor lastColor(String text) {
        if (text == null) return null;
        Matcher m = LAST_COLOR.matcher(text.replaceAll("[&§]x((?:[&§][0-9a-fA-F]){6})", ""));
        NamedTextColor last = null;
        while (m.find()) {
            if (m.group(1) != null) {
                TextColor hex = TextColor.fromHexString("#" + m.group(1));
                if (hex != null) last = NamedTextColor.nearestTo(hex);
            } else {
                String name = TAGS.get(Character.toLowerCase(m.group(2).charAt(0)));
                last = NamedTextColor.NAMES.value(name);
            }
        }
        return last;
    }
}
