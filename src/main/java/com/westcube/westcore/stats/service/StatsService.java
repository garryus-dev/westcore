package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.aggregate.DailyAggregator;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.PlayerRecord;
import com.westcube.westcore.stats.storage.PlayerSummary;
import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.StatsDatabase;
import com.westcube.westcore.stats.storage.StatsStorage;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Requêtes métier des statistiques. Les méthodes d'instance s'appellent depuis le thread principal :
 * elles lisent l'état en direct (connectés, TPS...) puis délèguent le calcul au thread de la base,
 * avec un cache de quelques secondes. Le calcul lui-même est dans des méthodes statiques testables.
 */
public final class StatsService {

    public static final int PLAYERS_PER_PAGE = 45;
    public static final int COHORTS = 5;
    private static final long DAY_MS = 86_400_000L;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");
    private static final String[] WEEKDAYS = {"Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"};

    private record Cached(long expires, CompletableFuture<?> future) {}

    private final StatsDatabase db;
    private final ZoneId zone;
    private final long cacheMs;
    private final long oneShotMs;
    private final Supplier<Views.LiveState> live;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public StatsService(StatsDatabase db, ZoneId zone, int cacheSeconds, int oneShotMinutes, Supplier<Views.LiveState> live) {
        this.db = db;
        this.zone = zone;
        this.cacheMs = cacheSeconds * 1000L;
        this.oneShotMs = oneShotMinutes * 60_000L;
        this.live = live;
    }

    public ZoneId zone() {
        return zone;
    }

    // ----------------------------------------------------------------- cache

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> cached(String key, Function<StatsStorage, T> query) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.expires() > now && !c.future().isCompletedExceptionally()) return (CompletableFuture<T>) c.future();
        if (cache.size() > 200) cache.values().removeIf(old -> old.expires() <= now);
        CompletableFuture<T> future = db.call(query);
        cache.put(key, new Cached(now + cacheMs, future));
        return future;
    }

    public void invalidate() {
        cache.clear();
    }

    // ------------------------------------------------------- appels publics

    public CompletableFuture<Views.Overview> overview() {
        Views.LiveState state = live.get();
        return cached("overview", s -> overview(s, zone, state));
    }

    public CompletableFuture<Views.Frequency> frequency(Views.FrequencyMode mode) {
        long now = live.get().now();
        return cached("frequency:" + mode, s -> frequency(s, zone, mode, now));
    }

    public CompletableFuture<Views.RetentionView> retention() {
        long now = live.get().now();
        return cached("retention", s -> retention(s, zone, now, oneShotMs));
    }

    public CompletableFuture<Views.PlayerPage> players(PlayerSummary.Sort sort, int page) {
        Views.LiveState state = live.get();
        return cached("players:" + sort + ":" + page, s -> players(s, sort, page, state));
    }

    public CompletableFuture<Views.PlayerCard> playerCard(UUID uuid) {
        Views.LiveState state = live.get();
        return cached("card:" + uuid, s -> playerCard(s, uuid, state));
    }

    public CompletableFuture<Views.Performance> performance(Views.PerfMode mode) {
        Views.LiveState state = live.get();
        return cached("performance:" + mode, s -> performance(s, zone, mode, state));
    }

    // ------------------------------------------------------------- calculs

    static LocalDate day(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }

    private static long start(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static DailyRecord empty(String serverId, LocalDate day, long now) {
        return new DailyRecord(serverId, day, 0, 0, 0, 0, 0, null, 0, 0, 0,
                null, null, null, null, null, null, null, null, null, now);
    }

    /**
     * Jours [from, to] : table journalière, jours manquants calculés à la volée,
     * jour en cours toujours calculé en direct ; jours sans aucune donnée = valeurs nulles.
     */
    public static Map<LocalDate, DailyRecord> days(StatsStorage s, ZoneId zone, LocalDate from, LocalDate to, long now) {
        LocalDate today = day(now, zone);
        LocalDate dataStart = s.firstSessionStart().map(ms -> day(ms, zone)).orElse(today.plusDays(1));
        Map<LocalDate, DailyRecord> stored = new HashMap<>();
        for (DailyRecord d : s.daily(from, to)) stored.put(d.day(), d);

        Map<LocalDate, DailyRecord> out = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.isAfter(today) || d.isBefore(dataStart)) {
                out.put(d, empty(s.serverId(), d, now));
            } else if (d.equals(today) || !stored.containsKey(d)) {
                out.put(d, DailyAggregator.compute(s, zone, d, now));
            } else {
                out.put(d, stored.get(d));
            }
        }
        return out;
    }

    public static Views.Overview overview(StatsStorage s, ZoneId zone, Views.LiveState state) {
        LocalDate today = day(state.now(), zone);
        Map<LocalDate, DailyRecord> days = days(s, zone, today.minusDays(8), today, state.now());
        return new Views.Overview(state.now(), state.online().size(), state.current(),
                days.get(today), days.get(today.minusDays(1)), List.copyOf(days.values()));
    }

    /** Tranches de 3 h alignées (0h, 3h, 6h...) dans le fuseau, la dernière contenant "now". */
    private static List<long[]> threeHourSlices(ZoneId zone, long now, int count) {
        ZonedDateTime current = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime last = current.withHour(current.getHour() / 3 * 3);
        List<long[]> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            ZonedDateTime a = last.minusHours(3L * i);
            out.add(new long[]{a.toInstant().toEpochMilli(), a.plusHours(3).toInstant().toEpochMilli()});
        }
        return out;
    }

    private static String sliceLabel(long from, long to, ZoneId zone) {
        int a = Instant.ofEpochMilli(from).atZone(zone).getHour();
        int b = Instant.ofEpochMilli(to).atZone(zone).getHour();
        return a + "h – " + b + "h";
    }

    private static int countBetween(List<PlayerRecord> players, long from, long to) {
        int n = 0;
        for (PlayerRecord p : players) if (p.firstSeen() >= from && p.firstSeen() < to) n++;
        return n;
    }

    public static Views.Frequency frequency(StatsStorage s, ZoneId zone, Views.FrequencyMode mode, long now) {
        LocalDate today = day(now, zone);
        List<Views.Bucket> buckets = new ArrayList<>();
        switch (mode) {
            case HOURS_24 -> {
                List<long[]> slices = threeHourSlices(zone, now, 8);
                long from = slices.getFirst()[0], to = slices.getLast()[1];
                List<SessionRecord> sessions = s.sessionsOverlapping(from, to);
                List<PlayerRecord> newcomers = s.playersFirstSeenBetween(from, to);
                for (long[] slice : slices) {
                    Calc.Activity a = Calc.activity(sessions, slice[0], slice[1], now);
                    buckets.add(new Views.Bucket(sliceLabel(slice[0], slice[1], zone), slice[0], slice[1], a.uniques(),
                            a.uniques(), a.peakOnline(), a.sessionsStarted(), a.playMs(),
                            countBetween(newcomers, slice[0], slice[1]), 1));
                }
            }
            case WEEKDAYS_30 -> {
                LocalDate from = today.minusDays(29);
                LocalDate dataStart = s.firstSessionStart().map(ms -> day(ms, zone)).orElse(today.plusDays(1));
                Map<LocalDate, DailyRecord> days = days(s, zone, from, today, now);
                for (DayOfWeek dow : DayOfWeek.values()) {
                    int count = 0, uniques = 0, peak = 0, sessions = 0, newPlayers = 0;
                    long play = 0;
                    for (DailyRecord d : days.values()) {
                        if (d.day().getDayOfWeek() != dow || d.day().isBefore(dataStart)) continue;
                        count++;
                        uniques += d.uniques();
                        peak = Math.max(peak, d.peakOnline());
                        sessions += d.sessions();
                        newPlayers += d.newPlayers();
                        play += d.playMs();
                    }
                    double avg = count == 0 ? 0 : (double) uniques / count;
                    buckets.add(new Views.Bucket(WEEKDAYS[dow.getValue() - 1], start(from, zone), start(today.plusDays(1), zone),
                            avg, uniques, peak, sessions, play, newPlayers, count));
                }
            }
            case DAYS_9 -> {
                for (DailyRecord d : days(s, zone, today.minusDays(8), today, now).values()) {
                    buckets.add(new Views.Bucket(d.day().format(DAY_LABEL), start(d.day(), zone), start(d.day().plusDays(1), zone),
                            d.uniques(), d.uniques(), d.peakOnline(), d.sessions(), d.playMs(), d.newPlayers(), 1));
                }
            }
            case WEEKS_9 -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate first = monday.minusWeeks(8);
                long from = start(first, zone), to = start(monday.plusWeeks(1), zone);
                List<SessionRecord> sessions = s.sessionsOverlapping(from, to);
                List<PlayerRecord> newcomers = s.playersFirstSeenBetween(from, to);
                for (int i = 0; i < 9; i++) {
                    LocalDate w = first.plusWeeks(i);
                    long a = start(w, zone), b = start(w.plusWeeks(1), zone);
                    Calc.Activity act = Calc.activity(sessions, a, b, now);
                    buckets.add(new Views.Bucket("Sem. du " + w.format(DAY_LABEL), a, b, act.uniques(), act.uniques(),
                            act.peakOnline(), act.sessionsStarted(), act.playMs(), countBetween(newcomers, a, b), 7));
                }
            }
        }
        return new Views.Frequency(mode, List.copyOf(buckets));
    }

    public static Views.RetentionView retention(StatsStorage s, ZoneId zone, long now, long oneShotMs) {
        LocalDate today = day(now, zone);
        Optional<Long> dataStartMs = s.firstSessionStart();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = monday.minusWeeks(COHORTS - 1);
        long from = start(first, zone);
        long to = start(monday.plusWeeks(1), zone);
        // Joueurs déjà venus avant la collecte : leur historique est incomplet, ils ne forment pas de cohorte
        long membersFrom = Math.max(from, dataStartMs.orElse(Long.MAX_VALUE));

        Map<UUID, List<SessionRecord>> byPlayer = new HashMap<>();
        for (SessionRecord session : s.sessionsOverlapping(from, Math.max(now, from) + 1)) {
            byPlayer.computeIfAbsent(session.uuid(), u -> new ArrayList<>()).add(session);
        }

        List<Retention.Cohort> cohorts = new ArrayList<>();
        int osHits = 0, osMeasured = 0;
        List<PlayerRecord> newcomers = membersFrom < to ? s.playersFirstSeenBetween(membersFrom, to) : List.of();
        for (int i = 0; i < COHORTS; i++) {
            LocalDate w = first.plusWeeks(i);
            long a = start(w, zone), b = start(w.plusWeeks(1), zone);
            List<PlayerRecord> members = newcomers.stream().filter(p -> p.firstSeen() >= a && p.firstSeen() < b).toList();
            Retention.Cohort c = Retention.cohort(w, members, byPlayer, zone, today, now, oneShotMs);
            cohorts.add(c);
            osHits += c.oneShot().hits();
            osMeasured += c.oneShot().measured();
        }
        return new Views.RetentionView(List.copyOf(cohorts), new Retention.Rate(osHits, osMeasured),
                dataStartMs.map(ms -> day(ms, zone)).orElse(null));
    }

    public static Views.PlayerPage players(StatsStorage s, PlayerSummary.Sort sort, int page, Views.LiveState state) {
        int total = s.countPlayers();
        int pages = Math.max(1, (total + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        int p = Math.clamp(page, 0, pages - 1);
        List<PlayerSummary> list = s.playerSummaries(state.now() - 30 * DAY_MS, state.now(), sort, p * PLAYERS_PER_PAGE, PLAYERS_PER_PAGE);
        return new Views.PlayerPage(sort, p, pages, total, list, Set.copyOf(state.online()));
    }

    public static Views.PlayerCard playerCard(StatsStorage s, UUID uuid, Views.LiveState state) {
        Optional<PlayerRecord> player = s.player(uuid);
        if (player.isEmpty()) return null;
        List<SessionRecord> sessions = s.sessionsOf(uuid, Integer.MAX_VALUE);
        long since = state.now() - 30 * DAY_MS;
        long total = 0, recent = 0, afk = 0;
        int recentCount = 0;
        boolean afkMeasured = false;
        long[] durations = new long[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            SessionRecord r = sessions.get(i);
            long d = r.durationMs(state.now());
            durations[i] = d;
            total += d;
            if (r.startMs() >= since) {
                recent += d;
                recentCount++;
            }
            if (r.afkMs() != null) {
                afk += r.afkMs();
                afkMeasured = true;
            }
        }
        return new Views.PlayerCard(player.get(), state.online().contains(uuid), sessions.size(), total, recent, recentCount,
                Calc.median(durations), afkMeasured ? afk : null, List.copyOf(sessions.subList(0, Math.min(10, sessions.size()))));
    }

    public static Views.Performance performance(StatsStorage s, ZoneId zone, Views.PerfMode mode, Views.LiveState state) {
        long now = state.now();
        List<long[]> slices = new ArrayList<>();
        if (mode == Views.PerfMode.HOURS_24) {
            slices = threeHourSlices(zone, now, 8);
        } else {
            LocalDate today = day(now, zone);
            for (int i = 6; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                slices.add(new long[]{start(d, zone), start(d.plusDays(1), zone)});
            }
        }
        List<SnapshotRecord> snapshots = s.snapshotsBetween(slices.getFirst()[0], slices.getLast()[1]);
        List<Views.PerfBucket> buckets = new ArrayList<>();
        for (long[] slice : slices) {
            List<SnapshotRecord> in = snapshots.stream().filter(r -> r.ts() >= slice[0] && r.ts() < slice[1]).toList();
            Calc.Perf p = Calc.perf(in);
            String label = mode == Views.PerfMode.HOURS_24
                    ? sliceLabel(slice[0], slice[1], zone)
                    : day(slice[0], zone).format(DAY_LABEL);
            buckets.add(p == null
                    ? new Views.PerfBucket(label, slice[0], slice[1], 0, null, null, null, null, null)
                    : new Views.PerfBucket(label, slice[0], slice[1], p.count(), p.msptMax(), p.msptAvg(), p.tpsMin(),
                    p.onlineAvg(), p.onlineMax()));
        }
        int timeouts = Calc.activity(s.sessionsOverlapping(now - DAY_MS, now + 1), now - DAY_MS, now + 1, now).timeouts();
        return new Views.Performance(mode, state.current(), timeouts, List.copyOf(buckets));
    }
}
