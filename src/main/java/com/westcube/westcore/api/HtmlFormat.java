package com.westcube.westcore.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Composant Adventure -> HTML (<span style="..."> uniquement), pour afficher
 * sur le site les couleurs & / &#rrggbb / MiniMessage des placeholders.
 */
final class HtmlFormat {

    private HtmlFormat() {}

    static String serialize(Component component) {
        StringBuilder out = new StringBuilder();
        append(out, component, Style.empty());
        return out.toString();
    }

    private static void append(StringBuilder out, Component component, Style parent) {
        // le style de l'enfant l'emporte, le parent ne comble que ce qui manque
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            String css = css(style);
            String content = escape(text.content());
            if (css.isEmpty()) {
                out.append(content);
            } else {
                out.append("<span style=\"").append(css).append("\">").append(content).append("</span>");
            }
        }
        for (Component child : component.children()) {
            append(out, child, style);
        }
    }

    private static String css(Style style) {
        StringBuilder css = new StringBuilder();
        TextColor color = style.color();
        if (color != null) css.append("color:").append(color.asHexString()).append(';');
        if (style.hasDecoration(TextDecoration.BOLD)) css.append("font-weight:bold;");
        if (style.hasDecoration(TextDecoration.ITALIC)) css.append("font-style:italic;");
        boolean under = style.hasDecoration(TextDecoration.UNDERLINED);
        boolean strike = style.hasDecoration(TextDecoration.STRIKETHROUGH);
        if (under || strike) {
            css.append("text-decoration:")
                    .append(under && strike ? "underline line-through" : under ? "underline" : "line-through")
                    .append(';');
        }
        return css.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
