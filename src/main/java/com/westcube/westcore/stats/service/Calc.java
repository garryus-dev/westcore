package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Calculs purs sur sessions et snapshots (aucun accès Bukkit ni base : testables). */
public final class Calc {

    private Calc() {}

    /**
     * Activité sur [from, to[.
     * Uniques, temps de jeu et pic : sessions qui chevauchent l'intervalle, découpées à ses bornes.
     * Sessions, médiane et moyenne : sessions commencées dans l'intervalle, durée complète.
     */
    public record Activity(
            int uniques,
            int sessionsStarted,
            int peakOnline,
            long playMs,
            Long afkMs,
            long medianSessionMs,
            long avgSessionMs,
            int timeouts,
            Set<UUID> players) {}

    public static Activity activity(List<SessionRecord> sessions, long from, long to, long now) {
        Set<UUID> players = new HashSet<>();
        List<long[]> events = new ArrayList<>();
        List<Long> started = new ArrayList<>();
        long play = 0;
        long afk = 0;
        boolean afkMeasured = false;
        int timeouts = 0;

        for (SessionRecord s : sessions) {
            long end = s.endMs() != null ? s.endMs() : now;
            long a = Math.max(s.startMs(), from);
            long b = Math.min(end, to);
            if (s.startMs() >= from && s.startMs() < to) started.add(Math.max(0, end - s.startMs()));
            if (s.endMs() != null && SessionRecord.TIMED_OUT.equals(s.quitReason()) && s.endMs() >= from && s.endMs() < to) {
                timeouts++;
            }
            // Une session de durée nulle commencée dans l'intervalle compte quand même comme connexion
            boolean overlaps = b > a || (s.startMs() >= from && s.startMs() < to);
            if (!overlaps) continue;
            players.add(s.uuid());
            long clipped = Math.max(0, b - a);
            play += clipped;
            if (s.afkMs() != null) {
                afkMeasured = true;
                long full = end - s.startMs();
                afk += full <= 0 ? 0 : Math.round((double) s.afkMs() * clipped / full);
            }
            events.add(new long[]{a, 1});
            events.add(new long[]{Math.max(a, b), -1});
        }

        // Fin avant début à horodatage égal : une reconnexion immédiate ne crée pas de faux pic
        events.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        int current = 0, peak = 0;
        for (long[] e : events) {
            current += (int) e[1];
            peak = Math.max(peak, current);
        }

        long[] durations = started.stream().mapToLong(Long::longValue).toArray();
        long avg = durations.length == 0 ? 0 : Arrays.stream(durations).sum() / durations.length;
        return new Activity(players.size(), durations.length, peak, play, afkMeasured ? afk : null,
                median(durations), avg, timeouts, players);
    }

    /** Médiane (moyenne des deux valeurs centrales si nombre pair), 0 si vide. */
    public static long median(long[] values) {
        if (values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /** Agrégat de performance d'une série de snapshots (null si la série est vide). */
    public record Perf(
            int count,
            double tpsAvg,
            double tpsMin,
            double msptAvg,
            double msptMax,
            long ramUsedAvgMb,
            long ramMaxMb,
            int chunksAvg,
            int entitiesAvg,
            Double pingAvg,
            double onlineAvg,
            int onlineMax) {}

    public static Perf perf(List<SnapshotRecord> snapshots) {
        if (snapshots.isEmpty()) return null;
        double tps = 0, tpsMin = Double.MAX_VALUE, mspt = 0, msptMax = 0, online = 0, ping = 0;
        long ram = 0, ramMax = 0, chunks = 0, entities = 0;
        int pingCount = 0, onlineMax = 0;
        for (SnapshotRecord s : snapshots) {
            tps += s.tps1m();
            tpsMin = Math.min(tpsMin, s.tps1m());
            mspt += s.msptAvg();
            msptMax = Math.max(msptMax, s.msptMax());
            ram += s.ramUsedMb();
            ramMax = Math.max(ramMax, s.ramMaxMb());
            chunks += s.chunks();
            entities += s.entities();
            online += s.online();
            onlineMax = Math.max(onlineMax, s.online());
            if (s.pingAvg() != null) {
                ping += s.pingAvg();
                pingCount++;
            }
        }
        int n = snapshots.size();
        return new Perf(n, tps / n, tpsMin, mspt / n, msptMax, ram / n, ramMax, (int) (chunks / n),
                (int) (entities / n), pingCount == 0 ? null : ping / pingCount, online / n, onlineMax);
    }
}
