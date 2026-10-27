package com.westcube.westcore.lastpos;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.DataFile;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Players;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dernière position "sûre" (vivant, au sol) de chaque joueur dans chaque monde,
 * et /tplastpos <joueur> <monde> pour l'y renvoyer après un délai sans bouger.
 * Positions en mémoire, écrites dans data/lastpos.yml toutes les 5 minutes si besoin.
 */
public class LastPosModule extends Module {

    public record Pos(double x, double y, double z, float yaw, float pitch) {

        Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }

        String serialize() {
            return x + ";" + y + ";" + z + ";" + yaw + ";" + pitch;
        }

        static Pos parse(String s) {
            String[] p = s.split(";");
            return new Pos(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Float.parseFloat(p[3]), Float.parseFloat(p[4]));
        }
    }

    private final DataFile file;
    private final Map<UUID, Map<String, Pos>> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private int tokens;

    public LastPosModule(WestCore plugin) {
        super(plugin, "lastpos");
        this.file = new DataFile(plugin, "lastpos.yml");
        permission("use", "westcube.tplastpos", PermissionDefault.OP, "Renvoyer un joueur à sa dernière position");
        command("tplastpos", "renvoyer un joueur à sa dernière position dans un monde", "use");
    }

    @Override
    protected void onEnable() {
        positions.clear();
        ConfigurationSection root = file.load().getConfigurationSection("players");
        if (root != null) {
            for (String uuid : root.getKeys(false)) {
                ConfigurationSection worlds = root.getConfigurationSection(uuid);
                if (worlds == null) continue;
                Map<String, Pos> map = new ConcurrentHashMap<>();
                for (String world : worlds.getKeys(false)) {
                    try {
                        map.put(world, Pos.parse(worlds.getString(world, "")));
                    } catch (RuntimeException ignored) {
                        // entrée corrompue : ignorée
                    }
                }
                positions.put(UUID.fromString(uuid), map);
            }
        }

        long sample = Math.max(10, cfg().getLong("sample-ticks", 40));
        repeat(() -> Bukkit.getOnlinePlayers().forEach(this::savePos), sample, sample);
        repeat(() -> {
            if (dirty) {
                dirty = false;
                file.saveAsync(snapshot());
            }
        }, 6000L, 6000L);
    }

    @Override
    protected void onDisable() {
        Bukkit.getOnlinePlayers().forEach(this::savePos);
        file.saveSync(snapshot());
        pending.clear();
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        positions.forEach((uuid, worlds) ->
                worlds.forEach((world, pos) -> yaml.set("players." + uuid + "." + world, pos.serialize())));
        return yaml;
    }

    public boolean has(UUID uuid, String world) {
        Map<String, Pos> map = positions.get(uuid);
        return map != null && map.containsKey(world);
    }

    public void importPositions(Map<UUID, Map<String, Pos>> imported) {
        imported.forEach((uuid, worlds) ->
                positions.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>()).putAll(worlds));
        file.saveAsync(snapshot());
    }

    // ------------------------------------------------------------ sauvegarde

    /** N'enregistre que des positions sûres : évite de renvoyer quelqu'un en plein vol ou dans le vide. */
    @SuppressWarnings("deprecation")
    private void savePos(Player p) {
        if (p.isDead() || !p.isOnGround()) return;

        Location loc = p.getLocation();
        String world = loc.getWorld().getName();
        Map<String, Pos> map = positions.computeIfAbsent(p.getUniqueId(), u -> new ConcurrentHashMap<>());
        Pos old = map.get(world);
        double min = cfg().getDouble("save-distance", 0.5);
        if (old != null) {
            double dx = loc.getX() - old.x(), dy = loc.getY() - old.y(), dz = loc.getZ() - old.z();
            if (dx * dx + dy * dy + dz * dz <= min * min) return;
        }
        map.put(world, new Pos(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        dirty = true;
    }

    // Avant toute téléportation (portail, /spawn, RTP...) : la boucle a pu manquer la position de départ
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        savePos(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        savePos(e.getPlayer());
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ /tplastpos

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(global("unknown-player"));
            return true;
        }
        String worldName = args[1];
        boolean other = sender instanceof Player sp && !sp.equals(target);

        Map<String, Pos> map = positions.get(target.getUniqueId());
        Pos dest = map == null ? null : map.get(worldName);
        if (dest == null) {
            target.sendMessage(msg("none"));
            if (other) sender.sendMessage(msg("none-other", "player", target.getName(), "world", worldName));
            return true;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(msg("world-not-loaded", "world", worldName));
            return true;
        }

        // Jeton unique : relancer la commande pendant le délai annule l'ancienne attente
        int token = ++tokens;
        pending.put(target.getUniqueId(), token);
        Location start = target.getLocation();
        int delay = cfg().getInt("tp-delay", 5);
        double tolerance = cfg().getDouble("tp-move-tolerance", 0.3);
        target.sendMessage(msg("countdown", "seconds", String.valueOf(delay)));

        int[] checks = {0};
        int totalChecks = delay * 5;
        // Vérification toutes les 4 ticks (5 fois par seconde)
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!Integer.valueOf(token).equals(pending.get(target.getUniqueId())) || !target.isOnline()) {
                task.cancel();
                return;
            }
            Location now = target.getLocation();
            if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > tolerance * tolerance) {
                cancel(target, raw("reason-moved"));
                task.cancel();
                return;
            }
            if (++checks[0] < totalChecks) return;

            task.cancel();
            pending.remove(target.getUniqueId());
            target.teleportAsync(dest.toLocation(world)).thenAccept(ok -> {
                if (!ok) return;
                target.sendMessage(msg("done"));
                if (other) sender.sendMessage(msg("done-other", "player", target.getName(), "world", worldName));
            });
        }, 4L, 4L);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        if (args.length == 1) return Players.onlineNames(args[0]);
        if (args.length == 2) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    // ------------------------------------------------- annulation sur action

    private void cancel(Player p, String reason) {
        if (pending.remove(p.getUniqueId()) == null) return;
        p.sendMessage(msg("cancelled", "reason", reason));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) cancel(p, raw("reason-damage"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) cancel(e.getPlayer(), raw("reason-interact"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        cancel(e.getPlayer(), raw("reason-break"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        cancel(e.getPlayer(), raw("reason-place"));
    }
}
