package com.westcube.westcore.afk;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissionDefault;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK à points d'activité : seul un cumul de points suffisant en peu de temps
 * fait sortir de l'AFK (anti-autoclicker / anti-machine). Tout est en mémoire.
 */
public class AfkModule extends Module {

    private static final class State {
        volatile long lastActivity;
        volatile boolean afk;
        volatile long afkSince;
        Location anchor;
        int points;
        long pointsStart;

        State(long now) {
            lastActivity = now;
        }
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> commandCooldown = new ConcurrentHashMap<>();

    private long idleMs, kickMs, kickExtendedMs, warningMs, wakeupTimeoutMs, cooldownMs;
    private double moveDistanceSq;
    private int threshold, pointsChat, pointsCommand, pointsInteract, pointsMovement;
    private boolean hideVanished;

    public AfkModule(WestCore plugin) {
        super(plugin, "afk");
        permission("use", "westcube.afk", PermissionDefault.OP, "Passer en AFK avec la commande");
        permission("bypass", "westcube.afk.bypass", PermissionDefault.OP, "Jamais AFK automatiquement ni expulsé");
        permission("longkick", "westcube.afk.longkick", PermissionDefault.OP, "Délai d'expulsion AFK allongé");
        command("afk", "passer en mode AFK", "use");
    }

    @Override
    protected void onEnable() {
        idleMs = cfg().getLong("idle-time", 300) * 1000L;
        kickMs = cfg().getLong("kick-time", 1800) * 1000L;
        kickExtendedMs = cfg().getLong("kick-time-extended", 3600) * 1000L;
        warningMs = cfg().getLong("kick-warning", 60) * 1000L;
        wakeupTimeoutMs = cfg().getLong("wakeup-timeout", 5) * 1000L;
        cooldownMs = cfg().getLong("afk-command-cooldown", 10) * 1000L;
        double distance = cfg().getDouble("move-distance", 1.0);
        moveDistanceSq = distance * distance;
        threshold = cfg().getInt("wakeup-threshold", 5);
        pointsChat = cfg().getInt("points.chat", 5);
        pointsCommand = cfg().getInt("points.command", 3);
        pointsInteract = cfg().getInt("points.interact", 0);
        pointsMovement = cfg().getInt("points.movement", 2);
        hideVanished = cfg().getBoolean("hide-vanished", true);

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            states.putIfAbsent(p.getUniqueId(), new State(now));
        }
        long period = Math.max(5, cfg().getLong("check-ticks", 30));
        repeat(this::check, period, period);
    }

    @Override
    protected void onDisable() {
        states.clear();
        commandCooldown.clear();
    }

    public boolean isAfk(UUID uuid) {
        State st = states.get(uuid);
        return st != null && st.afk;
    }

    /** Début de l'AFK courant (ms), 0 si le joueur n'est pas AFK. */
    public long afkSince(UUID uuid) {
        State st = states.get(uuid);
        return st != null && st.afk ? st.afkSince : 0;
    }

    /** Dernière activité réelle avant l'AFK courant (ms), 0 si le joueur n'est pas AFK. */
    public long inactiveSince(UUID uuid) {
        State st = states.get(uuid);
        return st != null && st.afk ? st.lastActivity : 0;
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), u -> new State(System.currentTimeMillis()));
    }

    // ------------------------------------------------------------- connexion

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        states.put(e.getPlayer().getUniqueId(), new State(System.currentTimeMillis()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
        commandCooldown.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ mouvement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        // Une téléportation n'est pas un mouvement : l'ancrage sera recréé au prochain déplacement
        state(e.getPlayer()).anchor = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        Player p = e.getPlayer();
        State st = state(p);
        Location to = e.getTo();

        // On compare à un point d'ancrage (pas à la position précédente),
        // sinon un joueur accroupi / qui nage lentement ne serait jamais détecté.
        if (st.anchor == null || st.anchor.getWorld() != to.getWorld()) {
            st.anchor = to.clone();
            return;
        }
        if (to.distanceSquared(st.anchor) < moveDistanceSq) return;
        st.anchor = to.clone();

        // Minecart / bateau en boucle = machine anti-AFK classique
        if (p.isInsideVehicle()) return;
        activity(p, pointsMovement);
    }

    // ------------------------------------------------------- autres actions

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) activity(p, pointsChat);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if ("afk".equals(plugin.commands().target(e.getMessage()))) return;
        activity(e.getPlayer(), pointsCommand);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (pointsInteract <= 0 || e.getAction() == Action.PHYSICAL) return;
        addPoints(e.getPlayer(), pointsInteract);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (pointsInteract <= 0) return;
        Object damager = e.getDamager() instanceof Projectile pr ? pr.getShooter() : e.getDamager();
        if (damager instanceof Player attacker) addPoints(attacker, pointsInteract);
        if (e.getEntity() instanceof Player victim) addPoints(victim, pointsInteract);
    }

    // ------------------------------------------------------------------ état

    /**
     * Hors AFK : l'action repousse le passage en AFK et le kick.
     * En AFK : seuls les points comptent, sinon une petite activité régulière
     * (machine, commande toutes les 5 min) éviterait le kick.
     */
    private void activity(Player p, int amount) {
        State st = state(p);
        if (!st.afk) {
            st.lastActivity = System.currentTimeMillis();
            return;
        }
        addPoints(p, amount);
    }

    private void addPoints(Player p, int amount) {
        if (amount <= 0) return;
        State st = state(p);
        if (!st.afk) return;

        long now = System.currentTimeMillis();
        if (st.pointsStart == 0 || now - st.pointsStart > wakeupTimeoutMs) {
            st.pointsStart = now;
            st.points = 0;
        }
        st.points += amount;
        if (st.points >= threshold) unsetAfk(p);
    }

    private void setAfk(Player p) {
        State st = state(p);
        st.afk = true;
        st.afkSince = System.currentTimeMillis();
        st.points = 0;
        st.pointsStart = 0;
        announce(p, msg("now-afk", "player", p.getName()));
    }

    private void unsetAfk(Player p) {
        State st = state(p);
        if (!st.afk) return;
        long now = System.currentTimeMillis();
        long duration = now - st.afkSince;
        st.afk = false;
        st.points = 0;
        st.pointsStart = 0;
        st.lastActivity = now;
        announce(p, msg("back", "player", p.getName(), "time", Text.duration(duration)));
    }

    private void announce(Player p, net.kyori.adventure.text.Component message) {
        if (hideVanished && isVanished(p)) {
            p.sendMessage(message);
            return;
        }
        Bukkit.broadcast(message);
    }

    private static boolean isVanished(Player p) {
        for (MetadataValue value : p.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------- vérification

    private void check() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = state(p);
            boolean bypass = has(p, "bypass");

            if (!st.afk) {
                if (!bypass && now - st.lastActivity >= idleMs) setAfk(p);
                continue;
            }

            String afkTime = Text.duration(now - st.afkSince);

            // Les bypass peuvent être AFK via /afk, mais ne sont jamais kick
            if (bypass) {
                p.sendActionBar(Text.mm(Text.fill(raw("actionbar"), "time", afkTime)));
                continue;
            }

            // Le kick se base sur la dernière activité réelle (idle-time inclus)
            long idle = now - st.lastActivity;
            long kickAfter = has(p, "longkick") ? kickExtendedMs : kickMs;

            if (idle >= kickAfter) {
                p.kick(Text.mm(Text.fill(raw("kick"), "time", Text.duration(idle))));
                continue;
            }

            long left = kickAfter - idle;
            if (left <= warningMs) {
                p.sendActionBar(Text.mm(Text.fill(raw("kick-warning"), "time", Text.duration(left))));
            } else {
                p.sendActionBar(Text.mm(Text.fill(raw("actionbar"), "time", afkTime)));
            }
        }
    }

    // --------------------------------------------------------------- /afk

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(global("players-only"));
            return true;
        }

        long now = System.currentTimeMillis();
        if (!has(p, "bypass")) {
            Long last = commandCooldown.get(p.getUniqueId());
            if (last != null && now - last < cooldownMs) {
                long left = (cooldownMs - (now - last) + 999) / 1000;
                p.sendMessage(msg("cooldown", "seconds", String.valueOf(left)));
                return true;
            }
        }
        commandCooldown.put(p.getUniqueId(), now);

        if (state(p).afk) unsetAfk(p);
        else setAfk(p);
        return true;
    }
}
