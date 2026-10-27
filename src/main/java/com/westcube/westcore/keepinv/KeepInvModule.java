package com.westcube.westcore.keepinv;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.DataFile;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Players;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /keepinv : garde l'inventaire et l'XP à la mort (remplace BetterKeepInventory).
 *   "self"  -> activé par le joueur (retiré s'il perd westcube.keepinv.toggle, ex. grade expiré)
 *   "admin" -> activé par un admin (jamais retiré automatiquement)
 * Permission "always" : garde l'inventaire sans toggle (grades qui l'ont en permanence).
 */
public class KeepInvModule extends Module {

    public static final String SELF = "self";
    public static final String ADMIN = "admin";

    private static final String TOGGLE = "toggle";
    private static final String ADMIN_TOGGLE = "admin-toggle";
    private static final String ALWAYS = "always";

    private final DataFile file;
    private final Map<UUID, String> enabled = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public KeepInvModule(WestCore plugin) {
        super(plugin, "keepinv");
        this.file = new DataFile(plugin, "keepinv.yml");
        permission(TOGGLE, "westcube.keepinv.toggle", PermissionDefault.OP, "Activer son propre KeepInventory");
        permission(ADMIN_TOGGLE, "westcube.keepinv.admin.toggle", PermissionDefault.OP, "Activer le KeepInventory d'un autre joueur");
        permission(ALWAYS, "westcube.keepinv.always", PermissionDefault.FALSE, "KeepInventory permanent, sans commande");
        command("keepinv", "activer ou désactiver le KeepInventory", TOGGLE, ADMIN_TOGGLE);
    }

    @Override
    protected void onEnable() {
        enabled.clear();
        ConfigurationSection players = file.load().getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                enabled.put(UUID.fromString(key), players.getString(key, SELF));
            }
        }
    }

    @Override
    protected void onDisable() {
        file.saveSync(snapshot());
        cooldown.clear();
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        enabled.forEach((u, origin) -> yaml.set("players." + u, origin));
        return yaml;
    }

    public boolean hasKeepInv(UUID uuid) {
        return enabled.containsKey(uuid);
    }

    private void set(UUID uuid, boolean state, String origin) {
        if (state) enabled.put(uuid, origin);
        else enabled.remove(uuid);
        file.saveAsync(snapshot());
    }

    public void importEntries(Map<UUID, String> entries) {
        enabled.putAll(entries);
        file.saveAsync(snapshot());
    }

    // ------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        boolean keep = has(p, ALWAYS);

        String origin = enabled.get(p.getUniqueId());
        if (origin != null) {
            if (SELF.equals(origin) && !has(p, TOGGLE)) {
                set(p.getUniqueId(), false, null);
                p.sendMessage(msg("revoked"));
            } else {
                keep = true;
            }
        }
        if (!keep) return;

        if (cfg().getBoolean("keep-items", true)) {
            e.setKeepInventory(true);
            e.getDrops().clear();
        }
        if (cfg().getBoolean("keep-exp", true)) {
            e.setKeepLevel(true);
            e.setDroppedExp(0);
        }
    }

    // Retrait automatique si le joueur a perdu l'accès (ex. grade VIP expiré)
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (SELF.equals(enabled.get(p.getUniqueId())) && !has(p, TOGGLE)) {
            set(p.getUniqueId(), false, null);
            p.sendMessage(msg("revoked"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ /keepinv

    @Override
    public boolean onCommand(CommandSender sender, String subcommand, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(msg("console-usage"));
                return true;
            }
            if (!has(p, TOGGLE)) {
                p.sendMessage(noPermission());
                return true;
            }

            long now = System.currentTimeMillis();
            long cooldownMs = cfg().getLong("toggle-cooldown", 3) * 1000L;
            Long last = cooldown.get(p.getUniqueId());
            if (last != null && now - last < cooldownMs) {
                long left = (cooldownMs - (now - last) + 999) / 1000;
                p.sendMessage(msg("cooldown", "seconds", String.valueOf(left)));
                return true;
            }
            cooldown.put(p.getUniqueId(), now);

            if (hasKeepInv(p.getUniqueId())) {
                set(p.getUniqueId(), false, null);
                p.sendMessage(msg("disabled"));
            } else {
                set(p.getUniqueId(), true, SELF);
                p.sendMessage(msg("enabled"));
            }
            return true;
        }

        // /keepinv <joueur> : toggle admin (joueur en ligne ou non)
        if (!has(sender, ADMIN_TOGGLE)) {
            sender.sendMessage(noPermission());
            return true;
        }
        OfflinePlayer target = Players.find(args[0]);
        if (target == null) {
            sender.sendMessage(global("unknown-player"));
            return true;
        }
        String name = String.valueOf(target.getName());
        Player online = target.getPlayer();

        if (hasKeepInv(target.getUniqueId())) {
            set(target.getUniqueId(), false, null);
            sender.sendMessage(msg("admin-disabled", "player", name));
            if (online != null) online.sendMessage(msg("player-disabled-admin"));
        } else {
            set(target.getUniqueId(), true, ADMIN);
            sender.sendMessage(msg("admin-enabled", "player", name));
            if (online != null) online.sendMessage(msg("player-enabled-admin"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        if (args.length == 1 && has(sender, ADMIN_TOGGLE)) return Players.onlineNames(args[0]);
        return List.of();
    }

    /** Valeur pour %westcube_keepinv%. */
    public String placeholder(UUID uuid) {
        return cfg().getString(hasKeepInv(uuid) ? "placeholder.on" : "placeholder.off", "");
    }
}
