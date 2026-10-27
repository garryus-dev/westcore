package com.westcube.westcore.migrate;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.dragon.DragonModule;
import com.westcube.westcore.keepinv.KeepInvModule;
import com.westcube.westcore.lastpos.LastPosModule;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import unique des données des anciens skripts depuis plugins/Skript/variables.csv :
 * kills du dragon, état du cycle, keepinv, dernières positions.
 * Les valeurs y sont sérialisées par Skript (hexadécimal) ; seuls les types utilisés sont décodés.
 */
public class SkriptMigrator {

    private static final Pattern LINE = Pattern.compile("^(?:\"((?:[^\"]|\"\")*)\"|([^,]*)),\\s*([^,]*),\\s*([0-9A-Fa-f]*)\\s*$");
    private static final String UUID_RE = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
    private static final Pattern KILLS = Pattern.compile("^enddragon\\.kills\\." + UUID_RE + "$");
    private static final Pattern DAMAGE = Pattern.compile("^enddragon\\.damage::" + UUID_RE + "$");
    private static final Pattern KEEPINV = Pattern.compile("^westkeepinv\\.enabled\\." + UUID_RE + "$");
    private static final Pattern LASTPOS = Pattern.compile("^westcube\\.lastpos::" + UUID_RE + "::(.+)$");

    private final WestCore plugin;

    public SkriptMigrator(WestCore plugin) {
        this.plugin = plugin;
    }

    public List<String> migrate(File csv) {
        List<String> report = new ArrayList<>();
        if (!csv.exists()) {
            report.add("Fichier introuvable : " + csv.getPath());
            return report;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.add("Lecture impossible : " + e.getMessage());
            return report;
        }

        Map<UUID, Integer> kills = new HashMap<>();
        Map<UUID, Double> damage = new HashMap<>();
        Map<UUID, String> keepInv = new HashMap<>();
        Map<UUID, Map<String, LastPosModule.Pos>> positions = new HashMap<>();
        String phase = null;
        long unlock = 0;
        boolean locked = false;
        int errors = 0;

        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1) != null ? m.group(1) : m.group(2).trim();
            String type = m.group(3).trim();
            String hex = m.group(4).toUpperCase();

            try {
                Matcher k;
                if ((k = KILLS.matcher(name)).matches()) {
                    kills.put(UUID.fromString(k.group(1)), (int) number(type, hex));
                } else if ((k = DAMAGE.matcher(name)).matches()) {
                    damage.put(UUID.fromString(k.group(1)), number(type, hex));
                } else if ((k = KEEPINV.matcher(name)).matches()) {
                    // true = ancien format v1, traité comme une activation admin (jamais retirée automatiquement)
                    String origin = type.equals("string") ? string(hex) : KeepInvModule.ADMIN;
                    keepInv.put(UUID.fromString(k.group(1)), origin.equals(KeepInvModule.SELF) ? KeepInvModule.SELF : KeepInvModule.ADMIN);
                } else if ((k = LASTPOS.matcher(name)).matches() && type.equals("location")) {
                    LastPosModule.Pos pos = location(hex);
                    if (pos != null) {
                        positions.computeIfAbsent(UUID.fromString(k.group(1)), u -> new HashMap<>()).put(k.group(2), pos);
                    } else {
                        errors++;
                    }
                } else if (name.equals("enddragon.phase") && type.equals("string")) {
                    phase = string(hex);
                } else if (name.equals("enddragon.unlock") && type.equals("date")) {
                    unlock = Long.parseLong(hex.substring(hex.length() - 16), 16);
                } else if (name.equals("enddragon.world.locked") && type.equals("boolean")) {
                    locked = hex.endsWith("01");
                }
            } catch (RuntimeException e) {
                errors++;
            }
        }

        DragonModule dragon = plugin.dragon();
        if (dragon.isEnabled()) {
            dragon.importKills(kills);
            dragon.importDamage(damage);
            DragonModule.Phase p = null;
            if (phase != null && !phase.isBlank()) {
                try {
                    p = DragonModule.Phase.valueOf(phase.toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    errors++;
                }
            }
            dragon.importState(p, unlock, locked);
            report.add("Dragon : " + kills.size() + " joueur(s), phase " + (p == null ? "aucune" : p) + ", verrouillé : " + locked);
        } else {
            report.add("Module dragon inactif : ignoré.");
        }

        if (plugin.keepInv().isEnabled()) {
            plugin.keepInv().importEntries(keepInv);
            // L'ancienne permission posée par le skript n'est plus utilisée par WestCore
            if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                for (UUID uuid : keepInv.keySet()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "lp user " + uuid + " permission unset betterkeepinventory.keep");
                }
            }
            report.add("KeepInv : " + keepInv.size() + " joueur(s) importé(s).");
        } else {
            report.add("Module keepinv inactif : ignoré.");
        }

        if (plugin.lastPos().isEnabled()) {
            plugin.lastPos().importPositions(positions);
            int count = positions.values().stream().mapToInt(Map::size).sum();
            report.add("LastPos : " + count + " position(s) pour " + positions.size() + " joueur(s).");
        } else {
            report.add("Module lastpos inactif : ignoré.");
        }

        if (errors > 0) report.add(errors + " entrée(s) illisible(s) ignorée(s).");
        report.add("Migration terminée.");
        return report;
    }

    // ------------------------------------------------------------- décodage

    private static double number(String type, String hex) {
        long bits = Long.parseUnsignedLong(hex, 16);
        return switch (type) {
            case "long" -> bits;
            case "double" -> Double.longBitsToDouble(bits);
            default -> throw new IllegalArgumentException("type " + type);
        };
    }

    private static String string(String hex) {
        byte[] b = bytes(hex);
        if (b.length >= 2 && (b[0] & 0xFF) == 0x80) {
            int len = b[1] & 0xFF;
            if (len == b.length - 2) return new String(b, 2, len, StandardCharsets.UTF_8);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Location sérialisée : champ name (monde), puis x / y / z (double), pitch / yaw (float). */
    private static LastPosModule.Pos location(String hex) {
        double x = dbl(hex, "017809");
        double y = dbl(hex, "017909");
        double z = dbl(hex, "017A09");
        float pitch = flt(hex, "706974636808");
        float yaw = flt(hex, "79617708");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
        return new LastPosModule.Pos(x, y, z, yaw, pitch);
    }

    private static double dbl(String hex, String marker) {
        int i = find(hex, marker);
        if (i < 0 || i + marker.length() + 16 > hex.length()) return Double.NaN;
        String h = hex.substring(i + marker.length(), i + marker.length() + 16);
        return Double.longBitsToDouble(Long.parseUnsignedLong(h, 16));
    }

    private static float flt(String hex, String marker) {
        int i = find(hex, marker);
        if (i < 0 || i + marker.length() + 8 > hex.length()) return 0f;
        String h = hex.substring(i + marker.length(), i + marker.length() + 8);
        return Float.intBitsToFloat((int) Long.parseLong(h, 16));
    }

    /** indexOf aligné sur un octet (position paire dans la chaîne hexadécimale). */
    private static int find(String hex, String marker) {
        int i = hex.indexOf(marker);
        while (i >= 0 && i % 2 != 0) i = hex.indexOf(marker, i + 1);
        return i;
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
