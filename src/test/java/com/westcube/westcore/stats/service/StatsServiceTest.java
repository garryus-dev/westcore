package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.aggregate.DailyAggregator;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.PlayerSummary;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.SqliteStatsStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Calculs du service et de l'agrégation sur une vraie base SQLite. */
class StatsServiceTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final long MIN = 60_000L, HOUR = 60 * MIN;

    @TempDir
    File dir;
    SqliteStatsStorage s;

    @BeforeEach
    void open() {
        s = new SqliteStatsStorage(new File(dir, "stats.db"), "test", () -> false);
        s.open();
    }

    @AfterEach
    void close() {
        s.close();
    }

    static long at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(PARIS).toInstant().toEpochMilli();
    }

    void play(UUID uuid, long start, long end) {
        s.upsertPlayer(uuid, "P" + uuid.toString().substring(0, 4), start, end);
        long id = s.openSession(uuid, "P", start, null, null, null, null, null);
        s.closeSession(id, end, 0L, "DISCONNECTED");
    }

    static Views.LiveState live(long now) {
        return new Views.LiveState(now, Set.of(),
                new SnapshotRecord("test", now, 0, 20, 20, 20, 10, 10, 1000, 4000, 0, 0, null));
    }

    @Test
    void catchUpAcrossMidnightAndIdempotent() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        UUID a = UUID.randomUUID();
        play(a, at(d, 23, 0), at(d.plusDays(1), 1, 0)); // 1 h le 01/10, 1 h le 02/10
        long now = at(d.plusDays(3), 4, 0);

        assertEquals(3, DailyAggregator.catchUp(s, PARIS, now)); // 01, 02 et 03/10
        assertEquals(0, DailyAggregator.catchUp(s, PARIS, now));

        DailyRecord first = s.daily(d, d).getFirst();
        assertEquals(HOUR, first.playMs());
        assertEquals(1, first.sessions());
        assertEquals(2 * HOUR, first.medianSessionMs());
        assertEquals(1, first.newPlayers());
        DailyRecord second = s.daily(d.plusDays(1), d.plusDays(1)).getFirst();
        assertEquals(1, second.uniques());
        assertEquals(0, second.sessions());
        assertEquals(0, second.newPlayers());
    }

    @Test
    void dstDayHas25Hours() {
        LocalDate change = LocalDate.of(2026, 10, 25); // passage à l'heure d'hiver
        UUID a = UUID.randomUUID();
        play(a, at(change.minusDays(1), 12, 0), at(change.plusDays(1), 12, 0));
        DailyAggregator.catchUp(s, PARIS, at(change.plusDays(2), 5, 0));
        assertEquals(25 * HOUR, s.daily(change, change).getFirst().playMs());
    }

    @Test
    void zoneChangeRecomputes() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        play(UUID.randomUUID(), at(d, 10, 0), at(d, 11, 0));
        long now = at(d.plusDays(2), 5, 0);
        assertEquals(2, DailyAggregator.catchUp(s, PARIS, now)); // 01 et 02/10
        // À New York il est encore le 02/10 au soir : seul le 01/10 est terminé, tout est recalculé
        assertEquals(1, DailyAggregator.catchUp(s, ZoneId.of("America/New_York"), now));
        assertEquals(1, s.daily(d.minusDays(5), d.plusDays(5)).size());
        assertEquals("America/New_York", s.meta("zone").orElseThrow());
    }

    @Test
    void nextRunRespectsZone() {
        StatsSettings settings = new StatsSettings("t", PARIS, 5, LocalTime.of(4, 0), 90, true, 10, 60,
                null, null, null, null, null, false, 7, 60);
        DailyAggregator agg = new DailyAggregator(null, settings, dir, null);
        LocalDate d = LocalDate.of(2026, 10, 12);
        assertEquals(at(d, 4, 0), agg.nextRun(at(d, 3, 59)));
        assertEquals(at(d.plusDays(1), 4, 0), agg.nextRun(at(d, 4, 0)));
    }

    @Test
    void frequencyModes() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        play(a, at(today.minusDays(2), 20, 0), at(today.minusDays(2), 21, 0));
        play(a, at(today, 9, 0), at(today, 10, 0));
        play(b, at(today, 9, 30), at(today, 11, 0));
        long now = at(today, 16, 20);

        Views.Frequency days = StatsService.frequency(s, PARIS, Views.FrequencyMode.DAYS_9, now);
        assertEquals(9, days.buckets().size());
        assertEquals(2, days.buckets().getLast().uniques());   // aujourd'hui, en direct
        assertEquals(1, days.buckets().get(6).uniques());       // avant-hier

        Views.Frequency hours = StatsService.frequency(s, PARIS, Views.FrequencyMode.HOURS_24, now);
        assertEquals(8, hours.buckets().size());
        assertEquals("15h – 18h", hours.buckets().getLast().label());
        assertEquals("9h – 12h", hours.buckets().get(5).label());
        assertEquals(2, hours.buckets().get(5).peak());
        assertEquals(2, hours.buckets().get(5).uniques());

        Views.Frequency weekdays = StatsService.frequency(s, PARIS, Views.FrequencyMode.WEEKDAYS_30, now);
        assertEquals(7, weekdays.buckets().size());
        assertEquals(2, weekdays.buckets().getFirst().value(), 1e-9); // lundi : un seul lundi depuis le début des données

        assertEquals(9, StatsService.frequency(s, PARIS, Views.FrequencyMode.WEEKS_9, now).buckets().size());
    }

    @Test
    void retentionIgnoresPlayersFromBeforeCollection() {
        LocalDate monday = LocalDate.of(2026, 9, 28);
        UUID veteran = UUID.randomUUID(), newbie = UUID.randomUUID();
        // Le vétéran a une première connexion Bukkit antérieure à la collecte
        s.upsertPlayer(veteran, "Vet", at(monday.minusYears(1), 10, 0), at(monday, 10, 0));
        long id = s.openSession(veteran, "Vet", at(monday, 10, 0), null, null, null, null, null);
        s.closeSession(id, at(monday, 11, 0), 0L, "DISCONNECTED");
        play(newbie, at(monday.plusDays(1), 10, 0), at(monday.plusDays(1), 10, 5));

        Views.RetentionView r = StatsService.retention(s, PARIS, at(monday.plusDays(14), 12, 0), 10 * MIN);
        assertEquals(StatsService.COHORTS, r.cohorts().size());
        int members = r.cohorts().stream().mapToInt(Retention.Cohort::size).sum();
        assertEquals(1, members);
        assertEquals(new Retention.Rate(1, 1), r.oneShot());
        assertEquals(monday, r.dataSince());
    }

    @Test
    void playersPageAndCard() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        for (int i = 0; i < 50; i++) play(UUID.randomUUID(), at(today, 8, i), at(today, 9, i));
        UUID a = UUID.randomUUID();
        play(a, at(today, 10, 0), at(today, 12, 0));
        play(a, at(today, 13, 0), at(today, 13, 30));
        long now = at(today, 18, 0);

        Views.PlayerPage page = StatsService.players(s, PlayerSummary.Sort.PLAYTIME, 7, live(now));
        assertEquals(2, page.pages());
        assertEquals(1, page.page()); // page bornée
        assertEquals(6, page.players().size());
        assertEquals(a, StatsService.players(s, PlayerSummary.Sort.PLAYTIME, 0, live(now)).players().getFirst().uuid());

        Views.PlayerCard card = StatsService.playerCard(s, a, live(now));
        assertEquals(2, card.sessions());
        assertEquals(150 * MIN, card.playMsTotal());
        assertEquals(75 * MIN, card.medianSessionMs());
        assertNull(StatsService.playerCard(s, UUID.randomUUID(), live(now)));
    }

    @Test
    void overviewAndPerformance() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        play(UUID.randomUUID(), at(today.minusDays(1), 10, 0), at(today.minusDays(1), 11, 0));
        for (int i = 0; i < 12; i++) {
            s.insertSnapshot(new SnapshotRecord("test", at(today, 9, i * 5), 1, 19.5, 19.8, 19.9, 20, 40 + i, 2000, 4000, 100, 200, 30.0));
        }
        long now = at(today, 12, 0);

        Views.Overview o = StatsService.overview(s, PARIS, live(now));
        assertEquals(9, o.lastDays().size());
        assertEquals(1, o.yesterday().uniques());
        assertEquals(0, o.today().uniques());

        Views.Performance p = StatsService.performance(s, PARIS, Views.PerfMode.HOURS_24, live(now));
        assertEquals(8, p.buckets().size());
        Views.PerfBucket morning = p.buckets().stream().filter(b -> b.label().equals("9h – 12h")).findFirst().orElseThrow();
        assertEquals(12, morning.samples());
        assertEquals(51.0, morning.msptMax());
        assertEquals(7, StatsService.performance(s, PARIS, Views.PerfMode.DAYS_7, live(now)).buckets().size());

        Map<LocalDate, DailyRecord> days = StatsService.days(s, PARIS, today.minusDays(3), today.plusDays(1), now);
        assertEquals(5, days.size());
        assertEquals(0, days.get(today.minusDays(3)).uniques()); // avant le début des données
    }
}
