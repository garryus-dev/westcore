package com.westcube.westcore.stats.collect;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.StatsDatabase;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/**
 * Relevés de performance, calés sur l'horloge (:00, :05, :10... pour 5 min) plutôt que sur les ticks,
 * qui ralentissent quand le serveur lag. Mesures sur le thread principal (peu coûteuses), écriture en async.
 */
public final class SnapshotCollector implements Listener {

    private static final long MB = 1024 * 1024;

    private final StatsDatabase db;
    private final SessionTracker tracker;
    private final long intervalMs;
    private long nextAt;
    private double worstTickMs;

    public SnapshotCollector(StatsDatabase db, SessionTracker tracker, int intervalMinutes) {
        this.db = db;
        this.tracker = tracker;
        this.intervalMs = intervalMinutes * 60_000L;
        this.nextAt = nextBoundary(System.currentTimeMillis());
    }

    private long nextBoundary(long now) {
        return (now / intervalMs + 1) * intervalMs;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        if (e.getTickDuration() > worstTickMs) worstTickMs = e.getTickDuration();
    }

    /** Appelé chaque seconde. */
    public void tick(long now) {
        if (now < nextAt) return;
        nextAt = nextBoundary(now);
        SnapshotRecord snapshot = sample(now);
        Map<Long, Long> afk = tracker.afkSnapshot(now);
        db.run(s -> {
            s.insertSnapshot(snapshot);
            s.updateSessionAfk(afk);
        });
    }

    /** État courant du serveur (thread principal) ; remet à zéro le pire tick. */
    public SnapshotRecord sample(long now) {
        double[] tps = Bukkit.getTPS();
        double msptAvg = Bukkit.getAverageTickTime();
        double msptMax = Math.max(worstTickMs, msptAvg);
        worstTickMs = 0;
        return current(now, tps, msptAvg, msptMax);
    }

    /** État courant sans toucher au pire tick (affichage dans le menu). */
    public SnapshotRecord peek(long now) {
        double msptAvg = Bukkit.getAverageTickTime();
        return current(now, Bukkit.getTPS(), msptAvg, Math.max(worstTickMs, msptAvg));
    }

    private SnapshotRecord current(long now, double[] tps, double msptAvg, double msptMax) {
        int online = 0;
        long ping = 0;
        for (SessionTracker.Live l : tracker.sessions()) {
            Player p = Bukkit.getPlayer(l.uuid());
            if (p == null) continue;
            online++;
            ping += p.getPing();
        }
        int chunks = 0, entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getChunkCount();
            entities += w.getEntityCount();
        }
        Runtime rt = Runtime.getRuntime();
        return new SnapshotRecord(db.serverId(), now, online,
                // Paper renvoie parfois 20.0000x : plafonné à 20
                round2(Math.min(20, tps[0])), round2(Math.min(20, tps[1])), round2(Math.min(20, tps[2])),
                round2(msptAvg), round2(msptMax),
                (rt.totalMemory() - rt.freeMemory()) / MB, rt.maxMemory() / MB,
                chunks, entities, online == 0 ? null : round2((double) ping / online));
    }

    private static double round2(double v) {
        return Math.round(Math.min(v, 1_000_000) * 100) / 100.0;
    }
}
