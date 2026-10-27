package com.westcube.westcore.stats.aggregate;

import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.service.Calc;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.StatsDatabase;
import com.westcube.westcore.stats.storage.StatsStorage;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Tâche quotidienne (heure configurée, fuseau configuré) :
 * calcule les jours terminés manquants dans la table "daily", purge les snapshots
 * trop anciens et les exports expirés. Rattrape les jours manqués (serveur éteint à l'heure prévue).
 * Si le fuseau change, la table journalière est entièrement recalculée.
 */
public final class DailyAggregator {

    private static final long DAY_MS = 86_400_000L;

    private final StatsDatabase db;
    private final StatsSettings settings;
    private final File exportsDir;
    private final Logger logger;
    private long nextRunAt;

    public DailyAggregator(StatsDatabase db, StatsSettings settings, File exportsDir, Logger logger) {
        this.db = db;
        this.settings = settings;
        this.exportsDir = exportsDir;
        this.logger = logger;
        this.nextRunAt = nextRun(System.currentTimeMillis());
    }

    /** Prochaine occurrence de l'heure d'agrégation (gère les changements d'heure). */
    public long nextRun(long now) {
        ZonedDateTime current = Instant.ofEpochMilli(now).atZone(settings.zone());
        ZonedDateTime candidate = current.toLocalDate().atTime(settings.aggregationTime()).atZone(settings.zone());
        if (!candidate.isAfter(current)) {
            candidate = current.toLocalDate().plusDays(1).atTime(settings.aggregationTime()).atZone(settings.zone());
        }
        return candidate.toInstant().toEpochMilli();
    }

    /** Appelé chaque seconde depuis le thread principal. */
    public void tick(long now) {
        if (now < nextRunAt) return;
        nextRunAt = nextRun(now);
        run(now);
    }

    /** Rattrapage + purges, sur le thread de la base. */
    public CompletableFuture<Void> run(long now) {
        return db.run(storage -> {
            int days = catchUp(storage, settings.zone(), now);
            int purged = storage.purgeSnapshotsBefore(now - settings.rawRetentionDays() * DAY_MS);
            int exports = purgeExports(now);
            if (days + purged + exports > 0) {
                logger.info("[Stats] Agrégation : " + days + " jour(s) calculé(s), " + purged
                        + " relevé(s) purgé(s), " + exports + " export(s) supprimé(s).");
            }
        });
    }

    /** Calcule les jours terminés absents de la table journalière. */
    public static int catchUp(StatsStorage storage, ZoneId zone, long now) {
        if (!zone.getId().equals(storage.meta("zone").orElse(null))) {
            storage.deleteDaily();
            storage.setMeta("zone", zone.getId());
        }
        LocalDate yesterday = day(now, zone).minusDays(1);
        Optional<LocalDate> from = storage.lastDailyDay().map(d -> d.plusDays(1))
                .or(() -> storage.firstSessionStart().map(ms -> day(ms, zone)));
        if (from.isEmpty()) return 0;

        int count = 0;
        for (LocalDate d = from.get(); !d.isAfter(yesterday); d = d.plusDays(1)) {
            storage.upsertDaily(compute(storage, zone, d, now));
            count++;
        }
        return count;
    }

    /** Agrégat d'un jour (aussi utilisé pour le jour en cours, calculé en direct). */
    public static DailyRecord compute(StatsStorage storage, ZoneId zone, LocalDate day, long now) {
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        Calc.Activity a = Calc.activity(storage.sessionsOverlapping(from, to), from, to, now);
        int newPlayers = storage.playersFirstSeenBetween(from, to).size();
        Calc.Perf p = Calc.perf(storage.snapshotsBetween(from, to));
        return new DailyRecord(storage.serverId(), day, a.uniques(), newPlayers, a.sessionsStarted(), a.peakOnline(),
                a.playMs(), a.afkMs(), a.medianSessionMs(), a.avgSessionMs(), a.timeouts(),
                p == null ? null : p.tpsAvg(),
                p == null ? null : p.tpsMin(),
                p == null ? null : p.msptAvg(),
                p == null ? null : p.msptMax(),
                p == null ? null : p.ramUsedAvgMb(),
                p == null ? null : p.ramMaxMb(),
                p == null ? null : p.chunksAvg(),
                p == null ? null : p.entitiesAvg(),
                p == null ? null : p.pingAvg(),
                now);
    }

    private int purgeExports(long now) {
        File[] files = exportsDir.listFiles((dir, name) -> name.startsWith("stats_") && name.endsWith(".xlsx"));
        if (files == null) return 0;
        int deleted = 0;
        long limit = now - settings.exportKeepDays() * DAY_MS;
        for (File f : files) {
            if (f.lastModified() < limit && f.delete()) deleted++;
        }
        return deleted;
    }

    public static LocalDate day(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }
}
