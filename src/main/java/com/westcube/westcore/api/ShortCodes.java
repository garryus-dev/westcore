package com.westcube.westcore.api;

import com.westcube.westcore.core.Colors;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codes courts du site, définis dans plugins/WestCore/codes-courts.yml.
 * Ce fichier est aussi la liste blanche : le site ne peut lire aucun autre placeholder.
 * Les méthodes resolve() appellent PlaceholderAPI : thread principal uniquement.
 */
final class ShortCodes {

    enum Scope { GLOBAL, JOUEUR }

    enum Format { TEXTE, HTML, NOMBRE, BRUT }

    /** Paramètre : valeur saisie sur le site -> valeur insérée dans le placeholder. */
    record Param(String name, Map<String, String> values) {}

    record Code(String id, String placeholder, Scope scope, boolean isPublic, Format format,
                String label, String description, List<Param> params) {}

    static final String FILE = "codes-courts.yml";

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,4})\\s*-\\s*(\\d{1,4})");
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z0-9_]+)}");
    /** Attributs déjà utilisés par les codes courts WordPress. */
    private static final Set<String> RESERVED = Set.of("code", "joueur", "defaut", "decimales");
    private static final int MAX_COMBINATIONS = 500;

    private final List<Code> codes;

    private ShortCodes(List<Code> codes) {
        this.codes = codes;
    }

    static ShortCodes load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        return parse(YamlConfiguration.loadConfiguration(file), plugin.getLogger());
    }

    static ShortCodes parse(YamlConfiguration yaml, Logger logger) {
        List<Code> codes = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection("codes");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                try {
                    codes.add(parse(id, section));
                } catch (IllegalArgumentException e) {
                    logger.warning(FILE + " : code \"" + id + "\" ignoré (" + e.getMessage() + ").");
                }
            }
        }
        logger.info(codes.size() + " code(s) court(s) chargé(s) depuis " + FILE + ".");
        return new ShortCodes(List.copyOf(codes));
    }

    private static Code parse(String id, ConfigurationSection s) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("identifiant : a-z, 0-9 et _ uniquement");
        String placeholder = s.getString("placeholder", "");
        if (placeholder.isBlank()) throw new IllegalArgumentException("placeholder manquant");

        Scope scope = switch (s.getString("portee", "global").toLowerCase(Locale.ROOT)) {
            case "global" -> Scope.GLOBAL;
            case "joueur" -> Scope.JOUEUR;
            default -> throw new IllegalArgumentException("portee doit valoir global ou joueur");
        };
        Format format;
        try {
            format = Format.valueOf(s.getString("format", "texte").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("format doit valoir texte, html, nombre ou brut");
        }

        List<Param> params = new ArrayList<>();
        ConfigurationSection ps = s.getConfigurationSection("params");
        long combinations = 1;
        if (ps != null) {
            for (String name : ps.getKeys(false)) {
                if (!ID.matcher(name).matches() || RESERVED.contains(name)) {
                    throw new IllegalArgumentException("nom de paramètre invalide ou réservé : " + name);
                }
                Map<String, String> values = values(ps, name);
                if (values.isEmpty()) throw new IllegalArgumentException("paramètre " + name + " sans valeur");
                params.add(new Param(name, values));
                combinations *= values.size();
            }
        }
        if (combinations > MAX_COMBINATIONS) {
            throw new IllegalArgumentException(combinations + " combinaisons, maximum " + MAX_COMBINATIONS);
        }
        Matcher tokens = TOKEN.matcher(placeholder);
        while (tokens.find()) {
            String name = tokens.group(1);
            if (params.stream().noneMatch(p -> p.name().equals(name))) {
                throw new IllegalArgumentException("{" + name + "} n'est pas déclaré dans params");
            }
        }

        return new Code(id, placeholder, scope, s.getBoolean("public", true), format,
                s.getString("label", id), s.getString("description", ""), List.copyOf(params));
    }

    /** "1-10", liste [a, b] ou table {site: placeholder}. */
    private static Map<String, String> values(ConfigurationSection ps, String name) {
        Map<String, String> values = new LinkedHashMap<>();
        if (ps.isConfigurationSection(name)) {
            ConfigurationSection map = ps.getConfigurationSection(name);
            for (String key : map.getKeys(false)) values.put(key, map.getString(key, key));
        } else if (ps.isList(name)) {
            for (Object o : ps.getList(name)) values.put(String.valueOf(o), String.valueOf(o));
        } else {
            String raw = ps.getString(name, "");
            Matcher range = RANGE.matcher(raw.trim());
            if (range.matches()) {
                int from = Integer.parseInt(range.group(1));
                int to = Integer.parseInt(range.group(2));
                for (int i = from; i <= to && values.size() <= MAX_COMBINATIONS; i++) {
                    values.put(String.valueOf(i), String.valueOf(i));
                }
            } else if (!raw.isBlank()) {
                values.put(raw, raw);
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!VALUE.matcher(e.getKey()).matches() || !VALUE.matcher(e.getValue()).matches()) {
                throw new IllegalArgumentException("valeur invalide pour " + name + " : " + e.getKey());
            }
        }
        return values;
    }

    boolean has(Scope scope) {
        return codes.stream().anyMatch(c -> c.scope() == scope);
    }

    // ------------------------------------------------------------------ JSON

    String catalogJson(long refreshSeconds, long playerCacheSeconds) {
        StringBuilder json = new StringBuilder("{\"refresh\":").append(refreshSeconds)
                .append(",\"cache_joueur\":").append(playerCacheSeconds)
                .append(",\"codes\":[");
        boolean first = true;
        for (Code c : codes) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(Json.quote(c.id()))
                    .append(",\"label\":").append(Json.quote(c.label()))
                    .append(",\"description\":").append(Json.quote(c.description()))
                    .append(",\"placeholder\":").append(Json.quote(c.placeholder()))
                    .append(",\"portee\":").append(Json.quote(c.scope().name().toLowerCase(Locale.ROOT)))
                    .append(",\"public\":").append(c.isPublic())
                    .append(",\"format\":").append(Json.quote(c.format().name().toLowerCase(Locale.ROOT)))
                    .append(",\"params\":[");
            for (int i = 0; i < c.params().size(); i++) {
                Param p = c.params().get(i);
                if (i > 0) json.append(',');
                json.append("{\"nom\":").append(Json.quote(p.name())).append(",\"valeurs\":[");
                int j = 0;
                for (String v : p.values().keySet()) {
                    if (j++ > 0) json.append(',');
                    json.append(Json.quote(v));
                }
                json.append("]}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    /**
     * Toutes les valeurs d'une portée : {"maj":..., "valeurs":{"id":"...", "id:p1:p2":"..."}}.
     * player null pour la portée globale.
     */
    String resolve(OfflinePlayer player, Scope scope) {
        StringBuilder json = new StringBuilder("{\"maj\":").append(System.currentTimeMillis() / 1000)
                .append(",\"valeurs\":{");
        boolean first = true;
        for (Code c : codes) {
            if (c.scope() != scope) continue;
            for (Map.Entry<String, String> e : expand(c).entrySet()) {
                if (!first) json.append(',');
                first = false;
                String value = PlaceholderAPI.setPlaceholders(player, e.getValue());
                // placeholder non reconnu (extension absente, joueur hors ligne...) : rien plutôt que %brut%
                if (value.equals(e.getValue())) value = "";
                json.append(Json.quote(e.getKey())).append(':').append(Json.quote(format(value, c.format())));
            }
        }
        return json.append("}}").toString();
    }

    /** Clé "id" ou "id:v1:v2" (valeurs côté site) -> placeholder complet. */
    private static Map<String, String> expand(Code c) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(c.id(), c.placeholder());
        for (Param p : c.params()) {
            Map<String, String> next = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : out.entrySet()) {
                for (Map.Entry<String, String> v : p.values().entrySet()) {
                    next.put(e.getKey() + ":" + v.getKey(), e.getValue().replace("{" + p.name() + "}", v.getValue()));
                }
            }
            out = next;
        }
        return out;
    }

    private static String format(String value, Format format) {
        if (value == null) return "";
        return switch (format) {
            case BRUT -> value;
            case HTML -> HtmlFormat.serialize(Colors.parse(value));
            case TEXTE, NOMBRE -> PlainTextComponentSerializer.plainText().serialize(Colors.parse(value)).trim();
        };
    }
}
