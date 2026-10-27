package com.westcube.westcore.staffcmd;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * /mob, /tuer, /entites, /claims, /westcmd (ex-westcmd_v2.sk).
 */
public class StaffCmdModule extends Module {

    private static final Pattern VARIANT = Pattern.compile("^[a-z_]+$");

    /** Catégories acceptées par /tuer en plus des types précis. */
    private static final Map<String, Predicate<Entity>> CATEGORIES = Map.of(
            "tout", e -> true,
            "all", e -> true,
            "monstres", e -> e instanceof Enemy,
            "monster", e -> e instanceof Enemy,
            "animaux", e -> e instanceof Animals,
            "animal", e -> e instanceof Animals,
            "items", e -> e instanceof Item,
            "item", e -> e instanceof Item);

    private static final Map<String, DyeColor> FRENCH_COLORS = Map.ofEntries(
            Map.entry("blanc", DyeColor.WHITE), Map.entry("orange", DyeColor.ORANGE),
            Map.entry("magenta", DyeColor.MAGENTA), Map.entry("bleu_clair", DyeColor.LIGHT_BLUE),
            Map.entry("jaune", DyeColor.YELLOW), Map.entry("vert_clair", DyeColor.LIME),
            Map.entry("rose", DyeColor.PINK), Map.entry("gris", DyeColor.GRAY),
            Map.entry("gris_clair", DyeColor.LIGHT_GRAY), Map.entry("cyan", DyeColor.CYAN),
            Map.entry("violet", DyeColor.PURPLE), Map.entry("bleu", DyeColor.BLUE),
            Map.entry("marron", DyeColor.BROWN), Map.entry("vert", DyeColor.GREEN),
            Map.entry("rouge", DyeColor.RED), Map.entry("noir", DyeColor.BLACK));

    public StaffCmdModule(WestCore plugin) {
        super(plugin, "staffcmd");
        permission("spawnmob", "westcube.spawnmob", PermissionDefault.OP, "Invoquer des entités");
        permission("spawnmob-boss", "westcube.spawnmob.boss", PermissionDefault.OP, "Invoquer des boss (dragon, wither)");
        permission("killmob", "westcube.killmob", PermissionDefault.OP, "Supprimer des entités");
        permission("entities", "westcube.entities", PermissionDefault.OP, "Compter les entités");
        permission("claims", "westcube.claims", PermissionDefault.OP, "Liste de ses claims");
        command("mob", "invoquer des entités", "spawnmob");
        command("tuer", "supprimer des entités autour de soi", "killmob");
        command("entites", "compter les entités autour de soi", "entities");
        command("claims", "liste de vos claims", "claims");
        command("westcmd", "aide des commandes WestCube");
    }

    private void send(CommandSender to, String miniMessage) {
        to.sendMessage(Text.mm(cfg().getString("prefix", "") + miniMessage));
    }

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        if (name.equals("westcmd")) {
            help(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(global("players-only"));
            return true;
        }
        switch (name) {
            case "mob" -> mob(p, args);
            case "tuer" -> kill(p, args);
            case "entites" -> count(p, args);
            case "claims" -> p.performCommand("claim list");
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /mob

    private void mob(Player p, String[] args) {
        if (args.length < 2) {
            send(p, "<red>Usage : " + Text.escape(label("mob")) + " <type> <nombre> [couleur ou variante]");
            return;
        }
        EntityType type = parseType(args[0]);
        if (type == null || !type.isSpawnable()) {
            send(p, "<red>Type d'entité inconnu : <white>" + Text.escape(args[0]));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            send(p, "<red>Nombre invalide.");
            return;
        }
        int max = cfg().getInt("max-spawn", 1024);
        if (amount < 1) {
            send(p, "<red>Le nombre doit être d'au moins 1.");
            return;
        }
        if (amount > max) {
            send(p, "<red>Maximum : <white>" + max + "</white> entités par commande.");
            return;
        }
        // Les boss demandent une permission à part
        if ((type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) && !has(p, "spawnmob-boss")) {
            send(p, "<red>Il faut la permission <white>" + Text.escape(perm("spawnmob-boss")) + "</white> pour invoquer un boss.");
            return;
        }

        // Argument optionnel : analysé une seule fois, pas à chaque entité
        DyeColor color = null;
        String variant = null;
        if (args.length > 2) {
            String opt = args[2].toLowerCase(Locale.ROOT);
            color = parseColor(opt);
            // Lettres et "_" uniquement : rien ne peut être injecté dans la commande /data
            if (VARIANT.matcher(opt).matches()) variant = opt;
        }

        Location loc = p.getLocation();
        for (int i = 0; i < amount; i++) {
            Entity e = loc.getWorld().spawnEntity(loc, type);
            if (args.length <= 2) continue;
            if (e instanceof Sheep sheep) {
                if (color != null) sheep.setColor(color);
            } else if (variant != null) {
                // Fonctionne pour toutes les variantes "minecraft:xxx" (vache, cochon, poulet, loup, chat, grenouille...)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "data merge entity " + e.getUniqueId() + " {variant:'minecraft:" + variant + "'}");
            }
        }
        send(p, "<green>" + amount + " <white>" + pretty(type) + "</white> invoqué(s).");
        if (args.length > 2 && color == null && variant == null) {
            send(p, "<gray>Couleur/variante <white>" + Text.escape(args[2]) + "</white> invalide, ignorée.");
        }
    }

    // ----------------------------------------------------------------- /tuer

    /** Entités épargnées par défaut : constructions et animaux des joueurs. */
    private static boolean isProtected(Entity e) {
        if (e.customName() != null) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        return e instanceof ArmorStand || e instanceof Hanging || e instanceof Villager;
    }

    private void kill(Player p, String[] args) {
        if (args.length < 1) {
            send(p, "<red>Usage : " + Text.escape(label("tuer")) + " <type|monstres|animaux|items|tout> [rayon] [force]");
            return;
        }
        String typeArg = args[0].toLowerCase(Locale.ROOT);
        Predicate<Entity> filter = CATEGORIES.get(typeArg);
        if (filter == null) {
            EntityType type = parseType(typeArg);
            if (type == null) {
                send(p, "<red>Type d'entité inconnu : <white>" + Text.escape(args[0]));
                return;
            }
            filter = e -> e.getType() == type;
        }
        Integer radius = radius(p, args, 1, 20);
        if (radius == null) return;
        boolean force = args.length > 2 && args[2].equalsIgnoreCase("force");

        int removed = 0, skipped = 0;
        for (Entity e : nearby(p, radius)) {
            if (!filter.test(e)) continue;
            if (!force && isProtected(e)) {
                skipped++;
                continue;
            }
            e.remove();
            removed++;
        }
        send(p, "<green>" + removed + " <white>entité(s) <gray>(" + Text.escape(typeArg) + ")</gray> <green>supprimée(s) dans un rayon de <white>"
                + radius + "</white> blocs.");
        if (skipped > 0) {
            send(p, "<gray>" + skipped + " entité(s) protégée(s) ignorée(s) (nommées, apprivoisées, villageois, décorations). "
                    + "Ajoutez <white>force</white> pour les inclure.");
        }
    }

    // -------------------------------------------------------------- /entites

    /** Quels types d'entités s'accumulent autour de moi ? (trouver une ferme ou une lag machine) */
    private void count(Player p, String[] args) {
        Integer radius = radius(p, args, 0, 50);
        if (radius == null) return;

        Map<EntityType, Integer> counts = new HashMap<>();
        int total = 0;
        for (Entity e : nearby(p, radius)) {
            counts.merge(e.getType(), 1, Integer::sum);
            total++;
        }
        if (total == 0) {
            send(p, "<gray>Aucune entité dans un rayon de <white>" + radius + "</white> blocs.");
            return;
        }
        send(p, "<white>" + total + " <gray>entité(s) dans un rayon de <white>" + radius + "</white> blocs :");
        List<Map.Entry<EntityType, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<EntityType, Integer>comparingByValue(Comparator.reverseOrder()));
        int top = cfg().getInt("entities-top", 10);
        for (int i = 0; i < Math.min(top, sorted.size()); i++) {
            Map.Entry<EntityType, Integer> entry = sorted.get(i);
            p.sendMessage(Text.mm("<dark_gray> • <white>" + pretty(entry.getKey()) + "</white> : <yellow>" + entry.getValue()));
        }
    }

    // --------------------------------------------------------------- /westcmd

    private void help(CommandSender sender) {
        send(sender, "<gray>Commandes disponibles :");
        boolean any = false;
        String[][] lines = {
                {"spawnmob", label("mob") + " <type> <nombre> [couleur|variante]", "invoquer des entités"},
                {"killmob", label("tuer") + " <type> [rayon] [force]", "supprimer des entités"},
                {"entities", label("entites") + " [rayon]", "compter les entités autour de vous"},
                {"claims", label("claims"), "liste de vos claims"},
        };
        for (String[] line : lines) {
            if (!has(sender, line[0])) continue;
            sender.sendMessage(Text.mm("<dark_gray> • <white>" + Text.escape(line[1]) + "</white> <gray>- " + line[2]));
            any = true;
        }
        if (!any) sender.sendMessage(Text.mm("<dark_gray> • <gray>Aucune commande disponible."));
    }

    // ------------------------------------------------------------ utilitaires

    private Integer radius(Player p, String[] args, int index, int def) {
        int radius = def;
        if (args.length > index) {
            try {
                radius = Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                send(p, "<red>Rayon invalide.");
                return null;
            }
        }
        int max = cfg().getInt("max-radius", 100);
        if (radius < 1) {
            send(p, "<red>Le rayon doit être d'au moins 1 bloc.");
            return null;
        }
        if (radius > max) {
            send(p, "<red>Rayon maximum : <white>" + max + "</white> blocs.");
            return null;
        }
        return radius;
    }

    /** Entités (hors joueurs) dans une sphère de rayon donné autour du joueur. */
    private static List<Entity> nearby(Player p, int radius) {
        Location center = p.getLocation();
        double sq = (double) radius * radius;
        return new ArrayList<>(center.getWorld().getNearbyEntities(center, radius, radius, radius,
                e -> !(e instanceof Player) && e.getLocation().distanceSquared(center) <= sq));
    }

    private static EntityType parseType(String input) {
        String key = input.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        NamespacedKey ns = NamespacedKey.fromString(key);
        if (ns == null) return null;
        EntityType type = Registry.ENTITY_TYPE.get(ns);
        return type == EntityType.UNKNOWN || type == EntityType.PLAYER ? null : type;
    }

    private static DyeColor parseColor(String input) {
        String key = input.replace(' ', '_');
        DyeColor fr = FRENCH_COLORS.get(key);
        if (fr != null) return fr;
        try {
            return DyeColor.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pretty(EntityType type) {
        return type.getKey().getKey().replace('_', ' ');
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        if ((name.equals("mob") || name.equals("tuer")) && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (name.equals("tuer")) {
                CATEGORIES.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().forEach(out::add);
            }
            Registry.ENTITY_TYPE.stream()
                    .filter(t -> t != EntityType.PLAYER && t != EntityType.UNKNOWN && (name.equals("tuer") || t.isSpawnable()))
                    .map(t -> t.getKey().getKey())
                    .filter(k -> k.startsWith(prefix))
                    .sorted()
                    .forEach(out::add);
            return out;
        }
        if (name.equals("mob") && args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(FRENCH_COLORS.keySet());
            out.addAll(List.of("warm", "cold", "temperate"));
            return out.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
        }
        if (name.equals("tuer") && args.length == 3) return List.of("force");
        return List.of();
    }
}
