package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.PlayerRecord;
import com.westcube.westcore.stats.storage.PlayerSummary;
import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Résultats immuables de StatsService : l'affichage (menu, console, export) ne fait que les mettre en forme. */
public final class Views {

    private Views() {}

    /** Valeurs lues sur le thread principal au moment de la demande. */
    public record LiveState(long now, Set<UUID> online, SnapshotRecord current) {}

    // ---------------------------------------------------------- vue d'ensemble

    public record Overview(
            long computedAt,
            int online,
            SnapshotRecord current,
            DailyRecord today,
            DailyRecord yesterday,
            List<DailyRecord> lastDays) {}

    // ---------------------------------------------------------- fréquentation

    public enum FrequencyMode {
        HOURS_24("24 h (tranches de 3 h)"),
        WEEKDAYS_30("Jours de la semaine (30 j)"),
        DAYS_9("9 derniers jours"),
        WEEKS_9("9 dernières semaines");

        public final String label;

        FrequencyMode(String label) {
            this.label = label;
        }

        public FrequencyMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Une colonne d'histogramme. value = joueurs uniques (moyenne par jour pour WEEKDAYS_30).
     * days = nombre de jours moyennés (WEEKDAYS_30), sinon 1.
     */
    public record Bucket(String label, long from, long to, double value, int uniques, int peak,
                         int sessions, long playMs, int newPlayers, int days) {}

    public record Frequency(FrequencyMode mode, List<Bucket> buckets) {}

    // -------------------------------------------------------------- rétention

    /** cohorts : de la plus ancienne à la plus récente ; oneShot : cumul sur ces cohortes. */
    public record RetentionView(List<Retention.Cohort> cohorts, Retention.Rate oneShot, LocalDate dataSince) {}

    // ---------------------------------------------------------------- joueurs

    public record PlayerPage(PlayerSummary.Sort sort, int page, int pages, int total,
                             List<PlayerSummary> players, Set<UUID> online) {}

    public record PlayerCard(
            PlayerRecord player,
            boolean online,
            int sessions,
            long playMsTotal,
            long playMs30d,
            int sessions30d,
            long medianSessionMs,
            Long afkMsTotal,
            List<SessionRecord> recent) {}

    // ------------------------------------------------------------ performance

    public enum PerfMode {
        HOURS_24("24 h (tranches de 3 h)"),
        DAYS_7("7 jours");

        public final String label;

        PerfMode(String label) {
            this.label = label;
        }

        public PerfMode next() {
            return this == HOURS_24 ? DAYS_7 : HOURS_24;
        }
    }

    /** Colonne de performance ; valeurs null si aucun relevé dans la tranche. */
    public record PerfBucket(String label, long from, long to, int samples, Double msptMax, Double msptAvg,
                             Double tpsMin, Double onlineAvg, Integer onlineMax) {}

    public record Performance(PerfMode mode, SnapshotRecord current, int timeouts24h, List<PerfBucket> buckets) {}
}
