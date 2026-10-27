package com.westcube.westcore.stats.export;

import com.westcube.westcore.stats.service.Calc;
import com.westcube.westcore.stats.service.Retention;
import com.westcube.westcore.stats.service.StatsService;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.PlayerRecord;
import com.westcube.westcore.stats.storage.PlayerSummary;
import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.StatsStorage;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Toutes les données d'un export, collectées en une fois sur le thread de la base. */
public record ExportData(
        ExportPeriod period,
        LocalDate from,
        LocalDate to,
        long generatedAt,
        ZoneId zone,
        String serverId,
        Summary summary,
        List<DailyRecord> days,
        List<HourRow> hours,
        List<PlayerRow> players,
        List<Retention.Cohort> cohorts,
        List<SnapshotRecord> snapshots) {

    private static final long HOUR_MS = 3_600_000L;

    public record Summary(int uniques, int newPlayers, int sessions, long playMs, Long afkMs, long medianMs, long avgMs,
                          int peak, LocalDate peakDay, double avgUniquesPerDay, Retention.Rate d1, Retention.Rate d7,
                          Retention.Rate d30, Retention.Rate oneShot, Calc.Perf perf, int timeouts) {}

    public record HourRow(LocalDateTime hour, int uniques, int peak, int sessions, long playMs,
                          Double onlineAvg, Double msptAvg, Double msptMax, Double tpsMin) {}

    public record PlayerRow(UUID uuid, String name, long firstSeen, long lastSeen, boolean newInPeriod, int sessions,
                            int activeDays, long playMs, long medianMs, Long afkMs, String version, String brand, String locale) {}

    private static long start(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static LocalDate day(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }

    public static ExportData collect(StatsStorage s, ZoneId zone, ExportPeriod period, long now, long oneShotMs) {
        LocalDate today = day(now, zone);
        LocalDate dataStart = s.firstSessionStart().map(ms -> day(ms, zone)).orElse(today);
        LocalDate from = period == ExportPeriod.ALL ? dataStart : today.minusDays(period.days - 1L);
        long fromMs = start(from, zone);
        long toMs = now + 1;

        List<DailyRecord> days = List.copyOf(StatsService.days(s, zone, from, today, now).values());
        List<SessionRecord> sessions = s.sessionsOverlapping(fromMs, toMs);
        List<SnapshotRecord> snapshots = s.snapshotsBetween(fromMs, toMs);

        List<Retention.Cohort> cohorts = cohorts(s, zone, from, today, now, oneShotMs);
        Summary summary = summary(s, sessions, snapshots, days, cohorts, fromMs, toMs, now);
        return new ExportData(period, from, today, now, zone, s.serverId(), summary, days,
                hours(sessions, snapshots, zone, fromMs, now), players(s, sessions, zone, fromMs, toMs, now),
                cohorts, snapshots);
    }

    private static Summary summary(StatsStorage s, List<SessionRecord> sessions, List<SnapshotRecord> snapshots,
                                   List<DailyRecord> days, List<Retention.Cohort> cohorts, long fromMs, long toMs, long now) {
        Calc.Activity a = Calc.activity(sessions, fromMs, toMs, now);
        DailyRecord peakDay = days.stream().max(Comparator.comparingInt(DailyRecord::peakOnline)).orElse(null);
        double avgUniques = days.stream().mapToInt(DailyRecord::uniques).average().orElse(0);
        int[] r = new int[8];
        for (Retention.Cohort c : cohorts) {
            r[0] += c.d1().hits();
            r[1] += c.d1().measured();
            r[2] += c.d7().hits();
            r[3] += c.d7().measured();
            r[4] += c.d30().hits();
            r[5] += c.d30().measured();
            r[6] += c.oneShot().hits();
            r[7] += c.oneShot().measured();
        }
        int newPlayers = s.playersFirstSeenBetween(fromMs, toMs).size();
        return new Summary(a.uniques(), newPlayers, a.sessionsStarted(), a.playMs(), a.afkMs(), a.medianSessionMs(),
                a.avgSessionMs(), peakDay == null ? 0 : peakDay.peakOnline(), peakDay == null ? null : peakDay.day(),
                avgUniques, new Retention.Rate(r[0], r[1]), new Retention.Rate(r[2], r[3]), new Retention.Rate(r[4], r[5]),
                new Retention.Rate(r[6], r[7]), Calc.perf(snapshots), a.timeouts());
    }

    /** Une ligne par heure ; chaque session n'est rangée que dans les heures qu'elle chevauche. */
    private static List<HourRow> hours(List<SessionRecord> sessions, List<SnapshotRecord> snapshots,
                                       ZoneId zone, long fromMs, long now) {
        Map<Long, List<SessionRecord>> byHour = new HashMap<>();
        for (SessionRecord session : sessions) {
            long end = session.endMs() != null ? session.endMs() : now;
            long h = Math.max(fromMs, session.startMs()) / HOUR_MS * HOUR_MS;
            do {
                byHour.computeIfAbsent(h, k -> new ArrayList<>()).add(session);
                h += HOUR_MS;
            } while (h < end);
        }
        Map<Long, List<SnapshotRecord>> snapsByHour = new HashMap<>();
        for (SnapshotRecord r : snapshots) snapsByHour.computeIfAbsent(r.ts() / HOUR_MS * HOUR_MS, k -> new ArrayList<>()).add(r);

        List<HourRow> out = new ArrayList<>();
        // Heures du fuseau ; le regroupement par heure UTC suppose un décalage horaire entier (cas d'Europe/Paris)
        ZonedDateTime hour = Instant.ofEpochMilli(fromMs).atZone(zone).truncatedTo(ChronoUnit.HOURS);
        while (hour.toInstant().toEpochMilli() <= now) {
            long a = hour.toInstant().toEpochMilli();
            long b = a + HOUR_MS;
            Calc.Activity act = Calc.activity(byHour.getOrDefault(a / HOUR_MS * HOUR_MS, List.of()), a, b, now);
            Calc.Perf p = Calc.perf(snapsByHour.getOrDefault(a / HOUR_MS * HOUR_MS, List.of()));
            out.add(new HourRow(hour.toLocalDateTime(), act.uniques(), act.peakOnline(), act.sessionsStarted(), act.playMs(),
                    p == null ? null : p.onlineAvg(), p == null ? null : p.msptAvg(),
                    p == null ? null : p.msptMax(), p == null ? null : p.tpsMin()));
            hour = hour.plusHours(1);
        }
        return out;
    }

    /** Joueurs actifs sur la période : temps de jeu découpé aux bornes, médiane sur les sessions commencées. */
    private static List<PlayerRow> players(StatsStorage s, List<SessionRecord> sessions, ZoneId zone,
                                           long fromMs, long toMs, long now) {
        Map<UUID, List<SessionRecord>> byPlayer = new LinkedHashMap<>();
        for (SessionRecord session : sessions) byPlayer.computeIfAbsent(session.uuid(), k -> new ArrayList<>()).add(session);
        Map<UUID, PlayerSummary> known = new HashMap<>();
        for (PlayerSummary p : s.playerSummaries(0, now, PlayerSummary.Sort.NEWEST, 0, Integer.MAX_VALUE)) known.put(p.uuid(), p);

        List<PlayerRow> out = new ArrayList<>();
        for (Map.Entry<UUID, List<SessionRecord>> e : byPlayer.entrySet()) {
            List<SessionRecord> list = e.getValue();
            Calc.Activity a = Calc.activity(list, fromMs, toMs, now);
            Set<LocalDate> activeDays = new HashSet<>();
            for (SessionRecord session : list) {
                long end = session.endMs() != null ? session.endMs() : now;
                for (LocalDate d = day(Math.max(session.startMs(), fromMs), zone); !d.isAfter(day(end, zone)); d = d.plusDays(1)) {
                    activeDays.add(d);
                }
            }
            SessionRecord last = list.getLast();
            PlayerSummary p = known.get(e.getKey());
            long firstSeen = p != null ? p.firstSeen() : list.getFirst().startMs();
            long lastSeen = p != null ? p.lastSeen() : last.startMs();
            out.add(new PlayerRow(e.getKey(), p != null ? p.name() : last.name(), firstSeen, lastSeen, firstSeen >= fromMs,
                    a.sessionsStarted(), activeDays.size(), a.playMs(), a.medianSessionMs(), a.afkMs(),
                    last.clientVersion(), last.clientBrand(), last.locale()));
        }
        out.sort(Comparator.comparingLong(PlayerRow::playMs).reversed().thenComparing(PlayerRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Cohortes hebdomadaires dont la semaine recoupe la période (joueurs arrivés après le début de la collecte). */
    private static List<Retention.Cohort> cohorts(StatsStorage s, ZoneId zone, LocalDate from, LocalDate today,
                                                  long now, long oneShotMs) {
        long dataStart = s.firstSessionStart().orElse(Long.MAX_VALUE);
        LocalDate firstWeek = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long a = start(firstWeek, zone);
        long b = start(lastWeek.plusWeeks(1), zone);
        List<PlayerRecord> newcomers = Math.max(a, dataStart) < b ? s.playersFirstSeenBetween(Math.max(a, dataStart), b) : List.of();
        Map<UUID, List<SessionRecord>> byPlayer = new HashMap<>();
        for (SessionRecord session : s.sessionsOverlapping(a, now + 1)) {
            byPlayer.computeIfAbsent(session.uuid(), k -> new ArrayList<>()).add(session);
        }
        List<Retention.Cohort> out = new ArrayList<>();
        for (LocalDate w = firstWeek; !w.isAfter(lastWeek); w = w.plusWeeks(1)) {
            long ws = start(w, zone), we = start(w.plusWeeks(1), zone);
            List<PlayerRecord> members = newcomers.stream().filter(p -> p.firstSeen() >= ws && p.firstSeen() < we).toList();
            out.add(Retention.cohort(w, members, byPlayer, zone, today, now, oneShotMs));
        }
        return out;
    }
}
