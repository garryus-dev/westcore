package com.westcube.westcore.dragon;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.DataFile;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Players;
import com.westcube.westcore.core.Text;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cycle de reset du monde du dragon, découpé en phases sauvegardées :
 * rewards -> teleport -> regen -> cooldown -> (ouvert).
 * Un redémarrage ou un /westcore reload en plein cycle reprend là où il en était.
 */
public class DragonModule extends Module {

    public enum Phase { REWARDS, TELEPORT, REGEN, COOLDOWN }

    private static final Set<Integer> COUNTDOWN_STEPS = Set.of(60, 30, 10, 5, 3, 2, 1);

    private final DataFile file;
    private final Map<UUID, Double> damage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private volatile Phase phase;
    private volatile long unlockAt;
    private volatile boolean locked;

    /** Chaque cycle lancé reçoit un jeton ; un cycle dont le jeton est périmé s'arrête de lui-même. */
    private int token;
    private BukkitTask countdown;

    private String worldName;

    public DragonModule(WestCore plugin) {
        super(plugin, "dragon");
        this.file = new DataFile(plugin, "dragon.yml");
        permission("status", "westcube.endstatus", PermissionDefault.OP, "Voir le statut du monde de l'End");
        permission("admin", "westcube.endadmin", PermissionDefault.OP, "Gérer le cycle et les statistiques du dragon");
        permission("bypass", "westcube.end.bypass", PermissionDefault.FALSE, "Entrer dans le monde du dragon même verrouillé");
        command("endstatus", "statut du monde de l'End", "status");
        command("dragonkills", "nombre de dragons combattus", "admin");
        command("dragonkillsset", "modifier le nombre de dragons combattus", "admin");
        command("endreset", "réinitialiser le cycle du dragon", "admin");
    }

    @Override
    protected void onEnable() {
        worldName = cfg().getString("world", "ressource_the_end_dragon");
        load();

        // Monde verrouillé sans phase (état incohérent) : on repart sur un cooldown terminé
        if (locked && phase == null) {
            phase = Phase.COOLDOWN;
            unlockAt = System.currentTimeMillis();
            save();
        }

        if (phase == Phase.REWARDS || phase == Phase.TELEPORT || phase == Phase.REGEN) {
            Phase resume = phase;
            later(() -> {
                plugin.getLogger().info("[Dragon] Cycle interrompu en phase " + resume + ", reprise...");
                sequence(resume);
            }, 100L);
        }

        repeat(() -> {
            if (phase == Phase.COOLDOWN && System.currentTimeMillis() >= unlockAt) unlock();
        }, 200L, 200L);
    }

    @Override
    protected void onDisable() {
        token++;
        countdown = null;
        file.saveSync(snapshot());
    }

    // ---------------------------------------------------------- persistance

    private void load() {
        YamlConfiguration yaml = file.load();
        String p = yaml.getString("phase");
        phase = p == null || p.isBlank() ? null : Phase.valueOf(p.toUpperCase());
        unlockAt = yaml.getLong("unlock-at", 0);
        locked = yaml.getBoolean("locked", false);

        damage.clear();
        ConfigurationSection dmg = yaml.getConfigurationSection("damage");
        if (dmg != null) {
            for (String k : dmg.getKeys(false)) damage.put(UUID.fromString(k), dmg.getDouble(k));
        }
        kills.clear();
        ConfigurationSection k = yaml.getConfigurationSection("kills");
        if (k != null) {
            for (String key : k.getKeys(false)) kills.put(UUID.fromString(key), k.getInt(key));
        }
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("phase", phase == null ? null : phase.name().toLowerCase());
        yaml.set("unlock-at", unlockAt);
        yaml.set("locked", locked);
        damage.forEach((u, d) -> yaml.set("damage." + u, d));
        kills.forEach((u, n) -> yaml.set("kills." + u, n));
        return yaml;
    }

    private void save() {
        file.saveAsync(snapshot());
    }

    // ------------------------------------------------------- suivi du combat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity() instanceof ComplexEntityPart part ? part.getParent() : e.getEntity();
        if (!(victim instanceof EnderDragon) || !victim.getWorld().getName().equals(worldName)) return;

        Object source = e.getDamager() instanceof Projectile pr ? pr.getShooter() : e.getDamager();
        if (!(source instanceof Player attacker)) return;
        damage.merge(attacker.getUniqueId(), e.getFinalDamage(), Double::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon) || !e.getEntity().getWorld().getName().equals(worldName)) return;
        if (phase != null) return; // anti double-trigger

        locked = true;
        Bukkit.broadcast(msg("defeated"));
        sequence(Phase.REWARDS);
    }

    // ------------------------------------------------------------- cycle

    private void sequence(Phase from) {
        int t = ++token;
        if (countdown != null) countdown.cancel();
        switch (from) {
            case REWARDS -> {
                setPhase(Phase.REWARDS);
                broadcastWorld(msg("preparing"));
                later(() -> {
                    if (t != token) return;
                    rewards();
                    teleportPhase(t);
                }, cfg().getLong("delay-rewards-seconds", 5) * 20L);
            }
            case TELEPORT -> teleportPhase(t);
            case REGEN -> regenPhase(t);
            default -> {}
        }
    }

    private void teleportPhase(int t) {
        setPhase(Phase.TELEPORT);
        int total = cfg().getInt("teleport-countdown-seconds", 60);
        int[] elapsed = {0};
        countdown = repeat(() -> {
            if (t != token) {
                cancelCountdown();
                return;
            }
            List<Player> players = worldPlayers();
            if (elapsed[0] >= total || players.isEmpty()) {
                cancelCountdown();
                regenPhase(t);
                return;
            }
            int left = total - elapsed[0];
            if (COUNTDOWN_STEPS.contains(left)) {
                String time;
                if (left == 60) time = "1 minute";
                else if (left == 1) time = "1 seconde";
                else time = left + " secondes";
                Component m = msg("countdown", "time", time);
                players.forEach(p -> p.sendMessage(m));
            }
            elapsed[0]++;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
    }

    private void regenPhase(int t) {
        setPhase(Phase.REGEN);

        // Toujours vider le monde avant la regen (retardataires, reconnexions...)
        for (Player p : worldPlayers()) {
            p.sendMessage(msg("teleporting"));
            sendToHub(p);
        }

        later(() -> {
            if (t != token) return;
            Bukkit.broadcast(msg("regen"));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    Text.fill(cfg().getString("regen-command", "mv regen {world}"), "world", worldName));

            // Reset du suivi de combat pour le prochain dragon
            damage.clear();
            save();

            later(() -> {
                if (t != token) return;
                long minutes = cfg().getLong("cooldown-minutes", 60);
                unlockAt = System.currentTimeMillis() + minutes * 60_000L;
                setPhase(Phase.COOLDOWN);
                Bukkit.broadcast(msg("cooldown", "minutes", String.valueOf(minutes)));
            }, cfg().getLong("delay-postregen-seconds", 10) * 20L);
        }, cfg().getLong("delay-preregen-seconds", 5) * 20L);
    }

    private void rewards() {
        double min = cfg().getDouble("min-damage", 10);
        double money = cfg().getDouble("reward-money", 1000);
        int xp = cfg().getInt("reward-xp", 1000);
        Economy economy = economy();

        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getKey());
            Player online = target.getPlayer();

            if (entry.getValue() >= min) {
                kills.merge(entry.getKey(), 1, Integer::sum);
                if (economy != null) {
                    economy.depositPlayer(target, money);
                } else {
                    plugin.getLogger().warning("[Dragon] Aucune économie Vault : " + target.getName() + " n'a pas reçu " + money + "$.");
                }
                if (online != null) {
                    online.giveExp(xp);
                    online.sendMessage(msg("reward", "xp", String.valueOf(xp), "money", formatMoney(money)));
                }
            } else if (online != null) {
                online.sendMessage(msg("not-enough-damage"));
            }
        }
        save();
    }

    private void unlock() {
        token++;
        cancelCountdown();
        phase = null;
        unlockAt = 0;
        locked = false;
        save();
        Bukkit.broadcast(msg("unlocked"));
    }

    private void setPhase(Phase p) {
        phase = p;
        save();
    }

    // ------------------------------------------------------------ utilitaires

    private List<Player> worldPlayers() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? List.of() : List.copyOf(world.getPlayers());
    }

    private void sendToHub(Player p) {
        String hub = cfg().getString("hub", "hub");
        String command = cfg().getString("hub-command", "");
        if (command != null && !command.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), Text.fill(command, "player", p.getName(), "hub", hub));
            return;
        }
        World world = Bukkit.getWorld(hub);
        if (world != null) p.teleportAsync(world.getSpawnLocation());
    }

    private void broadcastWorld(Component message) {
        worldPlayers().forEach(p -> p.sendMessage(message));
    }

    private static Economy economy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private static String formatMoney(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    /** Minutes restantes avant réouverture (0 si pas en cooldown). */
    public long minutesLeft() {
        if (phase != Phase.COOLDOWN) return 0;
        long ms = unlockAt - System.currentTimeMillis();
        return ms <= 0 ? 0 : (long) Math.ceil(ms / 60_000.0);
    }

    private String lockReason() {
        long mins = minutesLeft();
        return mins > 0 ? Text.fill(raw("reason-cooldown"), "minutes", String.valueOf(mins)) : raw("reason-regen");
    }

    public boolean isLocked() {
        return locked;
    }

    public int kills(UUID uuid) {
        return kills.getOrDefault(uuid, 0);
    }

    // ------------------------------------------------------- accès au monde

    private boolean isDragonWorld(World world) {
        return world != null && world.getName().equals(worldName);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!locked || !isDragonWorld(e.getTo().getWorld()) || isDragonWorld(e.getFrom().getWorld())) return;
        if (e.getPlayer().hasPermission(perm("bypass"))) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(msg("access-denied", "reason", lockReason()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        kickIfLocked(e.getPlayer());
    }

    // Un joueur déconnecté dans l'End pendant le reset réapparaîtrait dans le monde verrouillé
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        later(() -> {
            if (p.isOnline()) kickIfLocked(p);
        }, 1L);
    }

    private void kickIfLocked(Player p) {
        if (!locked || !isDragonWorld(p.getWorld()) || p.hasPermission(perm("bypass"))) return;
        // Pendant la phase "teleport", les joueurs présents ont encore le droit de rester
        if (phase == Phase.REWARDS || phase == Phase.TELEPORT) return;
        sendToHub(p);
        p.sendMessage(msg("access-denied", "reason", lockReason()));
    }

    // ------------------------------------------------------------ commandes

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "endstatus" -> {
                if (locked) {
                    sender.sendMessage(msg("status-locked", "reason", lockReason()));
                } else {
                    sender.sendMessage(msg("status-open"));
                }
            }
            case "dragonkills" -> {
                OfflinePlayer target;
                if (args.length > 0) {
                    target = Players.find(args[0]);
                    if (target == null) {
                        sender.sendMessage(global("unknown-player"));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(Text.mm(cfg().getString("prefix", "") + "<gray>Usage : <white>"
                            + Text.escape(label("dragonkills")) + " <joueur>"));
                    return true;
                }
                sender.sendMessage(msg("kills", "player", String.valueOf(target.getName()),
                        "kills", String.valueOf(kills(target.getUniqueId()))));
            }
            case "dragonkillsset" -> {
                if (args.length < 2) {
                    sender.sendMessage(Text.mm(cfg().getString("prefix", "") + "<gray>Usage : <white>"
                            + Text.escape(label("dragonkillsset")) + " <joueur> <nombre>"));
                    return true;
                }
                OfflinePlayer target = Players.find(args[0]);
                if (target == null) {
                    sender.sendMessage(global("unknown-player"));
                    return true;
                }
                int value;
                try {
                    value = Math.max(0, Integer.parseInt(args[1]));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Text.mm(cfg().getString("prefix", "") + "<red>Nombre invalide."));
                    return true;
                }
                kills.put(target.getUniqueId(), value);
                save();
                sender.sendMessage(msg("kills-set", "player", String.valueOf(target.getName()), "kills", String.valueOf(value)));
            }
            case "endreset" -> {
                damage.clear();
                sender.sendMessage(msg("reset"));
                if (locked) {
                    unlock();
                } else {
                    token++;
                    cancelCountdown();
                    phase = null;
                    unlockAt = 0;
                    save();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        if (args.length == 1 && (name.equals("dragonkills") || name.equals("dragonkillsset"))) {
            return Players.onlineNames(args[0]);
        }
        return List.of();
    }

    // ------------------------------------------------------------- migration

    public void importState(Phase phase, long unlockAt, boolean locked) {
        this.phase = phase;
        this.unlockAt = unlockAt;
        this.locked = locked;
        save();
    }

    public void importKills(Map<UUID, Integer> imported) {
        kills.putAll(imported);
        save();
    }

    public void importDamage(Map<UUID, Double> imported) {
        damage.putAll(imported);
        save();
    }
}
