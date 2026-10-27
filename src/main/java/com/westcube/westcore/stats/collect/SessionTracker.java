package com.westcube.westcore.stats.collect;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.afk.AfkModule;
import com.westcube.westcore.stats.storage.StatsDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Sessions en cours (thread principal). Les sessions survivent à un /westcore reload :
 * le tracker appartient au module et n'est rattaché qu'à la nouvelle base au redémarrage.
 */
public final class SessionTracker implements Listener {

    /** Session ouverte ; id complété par le thread de la base (les écritures suivantes passent après, dans l'ordre). */
    public static final class Live {
        final UUID uuid;
        final String name;
        final long startMs;
        final long firstSeen;
        final CompletableFuture<Long> id;
        String brand;
        String locale;
        /** Vrai dès que le module afk a suivi cette session (il peut être arrêté avant nous à l'extinction). */
        boolean afkTracked;
        long afkDone;
        long afkStart;
        long lastAfkEnd;

        Live(UUID uuid, String name, long startMs, long firstSeen, CompletableFuture<Long> id, String brand, String locale) {
            this.uuid = uuid;
            this.name = name;
            this.startMs = startMs;
            this.firstSeen = firstSeen;
            this.id = id;
            this.brand = brand;
            this.locale = locale;
        }

        long afkMs(long now) {
            return afkDone + (afkStart > 0 ? Math.max(0, now - afkStart) : 0);
        }

        public UUID uuid() { return uuid; }
        public long startMs() { return startMs; }
    }

    private final WestCore plugin;
    private final Map<UUID, Live> live = new ConcurrentHashMap<>();
    private StatsDatabase db;
    private Predicate<Player> exempt = p -> false;
    private boolean countIdleTime;

    public SessionTracker(WestCore plugin) {
        this.plugin = plugin;
    }

    /** Rattache le tracker à la base (démarrage ou reload) et ouvre les sessions manquantes. */
    public void attach(StatsDatabase db, Predicate<Player> exempt, boolean countIdleTime) {
        this.db = db;
        this.exempt = exempt;
        this.countIdleTime = countIdleTime;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!live.containsKey(p.getUniqueId()) && !exempt.test(p)) open(p, now);
        }
        // Sessions dont le joueur n'est plus là (cas limite) : fermées maintenant
        for (Live l : live.values()) {
            if (Bukkit.getPlayer(l.uuid) == null) close(live.remove(l.uuid), now, "DISCONNECTED");
        }
    }

    public void detach() {
        db = null;
    }

    public Collection<Live> sessions() {
        return live.values();
    }

    public boolean isTracked(UUID uuid) {
        return live.containsKey(uuid);
    }

    // ------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (db == null || exempt.test(p)) return;
        open(p, System.currentTimeMillis());
        // Marque et langue arrivent souvent après le join
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshClient(p), 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Live l = live.remove(e.getPlayer().getUniqueId());
        if (l != null) close(l, System.currentTimeMillis(), e.getReason().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocale(PlayerLocaleChangeEvent e) {
        Live l = live.get(e.getPlayer().getUniqueId());
        if (l == null || db == null) return;
        String locale = e.locale().toString().toLowerCase(Locale.ROOT);
        if (locale.equals(l.locale)) return;
        l.locale = locale;
        db.run(s -> s.updateSessionClient(l.id.join(), null, locale));
    }

    private void refreshClient(Player p) {
        Live l = live.get(p.getUniqueId());
        if (l == null || db == null || !p.isOnline()) return;
        String brand = p.getClientBrandName();
        String locale = p.locale().toString().toLowerCase(Locale.ROOT);
        if (Objects.equals(brand, l.brand) && Objects.equals(locale, l.locale)) return;
        l.brand = brand;
        l.locale = locale;
        db.run(s -> s.updateSessionClient(l.id.join(), brand, locale));
    }

    // ------------------------------------------------------------- sessions

    private void open(Player p, long now) {
        UUID uuid = p.getUniqueId();
        String name = p.getName();
        long played = p.getFirstPlayed();
        long firstSeen = played > 0 ? Math.min(played, now) : now;
        String host = hostname(p.getVirtualHost());
        Object[] version = clientVersion(p);
        String brand = p.getClientBrandName();
        String locale = p.locale().toString().toLowerCase(Locale.ROOT);

        CompletableFuture<Long> id = db.call(s -> {
            s.upsertPlayer(uuid, name, firstSeen, now);
            return s.openSession(uuid, name, now, host, (String) version[0], (Integer) version[1], brand, locale);
        });
        live.put(uuid, new Live(uuid, name, now, firstSeen, id, brand, locale));
    }

    private void close(Live l, long end, String reason) {
        if (db == null) return;
        Long afk = l.afkTracked || afkMeasured() ? l.afkMs(end) : null;
        db.run(s -> {
            s.closeSession(l.id.join(), end, afk, reason);
            s.upsertPlayer(l.uuid, l.name, l.firstSeen, end);
        });
    }

    /** Arrêt du serveur : ferme toutes les sessions (écritures en file, vidée par StatsDatabase.shutdown). */
    public void closeAll(String reason) {
        long now = System.currentTimeMillis();
        for (UUID uuid : live.keySet()) {
            Live l = live.remove(uuid);
            if (l != null) close(l, now, reason);
        }
    }

    // ------------------------------------------------------------------- AFK

    private AfkModule afkModule() {
        AfkModule afk = plugin.afk();
        return afk != null && afk.isEnabled() ? afk : null;
    }

    private boolean afkMeasured() {
        return afkModule() != null;
    }

    /** Appelé chaque seconde : suit les entrées / sorties d'AFK détectées par le module afk. */
    public void tickAfk(long now) {
        AfkModule afk = afkModule();
        if (afk == null) return;
        for (Live l : live.values()) {
            l.afkTracked = true;
            boolean isAfk = afk.isAfk(l.uuid);
            if (isAfk && l.afkStart == 0) {
                long start = countIdleTime ? afk.inactiveSince(l.uuid) : afk.afkSince(l.uuid);
                if (start <= 0) start = now;
                // Jamais avant le début de la session ni avant la fin du précédent AFK
                l.afkStart = Math.min(now, Math.max(start, Math.max(l.startMs, l.lastAfkEnd)));
            } else if (!isAfk && l.afkStart > 0) {
                l.afkDone += Math.max(0, now - l.afkStart);
                l.afkStart = 0;
                l.lastAfkEnd = now;
            }
        }
    }

    /** Temps AFK courant des sessions ouvertes (sauvegardé avec chaque snapshot). */
    public Map<Long, Long> afkSnapshot(long now) {
        Map<Long, Long> out = new HashMap<>();
        for (Live l : live.values()) {
            Long id = l.id.getNow(null);
            if (id != null && l.afkTracked) out.put(id, l.afkMs(now));
        }
        return out;
    }

    // ----------------------------------------------------------- utilitaires

    /** Adresse du serveur tapée par le joueur (jamais son IP). */
    private static String hostname(InetSocketAddress virtualHost) {
        if (virtualHost == null) return null;
        String host = virtualHost.getHostString();
        int nul = host.indexOf('\0'); // marqueurs Forge / BungeeCord
        if (nul >= 0) host = host.substring(0, nul);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    private Object[] clientVersion(Player p) {
        if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                Object[] v = ViaHook.version(p.getUniqueId());
                if (v != null) return v;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[Stats] ViaVersion indisponible", t);
            }
        }
        // Sans ViaVersion, le client a forcément la version du serveur
        return new Object[]{Bukkit.getMinecraftVersion(), p.getProtocolVersion()};
    }
}
