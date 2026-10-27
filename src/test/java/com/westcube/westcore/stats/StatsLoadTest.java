package com.westcube.westcore.stats;

import com.westcube.westcore.stats.service.StatsService;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.PlayerSummary;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.SqliteStatsStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Chaque requête du menu sur 90 jours d'historique doit rester rapide (exécutée hors thread principal). */
class StatsLoadTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final long BUDGET_MS = 500;

    @TempDir
    static File dir;
    static SqliteStatsStorage s;
    static long now;
    static int sessions;

    @BeforeAll
    static void seed() {
        s = new SqliteStatsStorage(new File(dir, "stats.db"), "westcube", () -> false);
        s.open();
        now = System.currentTimeMillis();
        sessions = DemoData.generate(s, PARIS, 90, now, 42);
    }

    @AfterAll
    static void close() {
        s.close();
    }

    static Views.LiveState live() {
        return new Views.LiveState(now, Set.of(),
                new SnapshotRecord("westcube", now, 5, 20, 20, 20, 12, 30, 3000, 8192, 2000, 3000, 50.0));
    }

    static <T> T timed(String what, Supplier<T> query) {
        long t0 = System.nanoTime();
        T result = query.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  %-28s %4d ms%n", what, ms);
        assertTrue(ms < BUDGET_MS, what + " : " + ms + " ms");
        return result;
    }

    @Test
    void menuQueriesStayFast() {
        System.out.println("Base de test : " + sessions + " sessions, " + s.countPlayers() + " joueurs, 90 jours");
        assertTrue(sessions > 3000);

        Views.Overview o = timed("vue d'ensemble", () -> StatsService.overview(s, PARIS, live()));
        assertEquals(9, o.lastDays().size());
        for (Views.FrequencyMode mode : Views.FrequencyMode.values()) {
            timed("fréquentation " + mode, () -> StatsService.frequency(s, PARIS, mode, now));
        }
        Views.RetentionView r = timed("rétention", () -> StatsService.retention(s, PARIS, now, 10 * 60_000L));
        assertTrue(r.cohorts().stream().anyMatch(c -> c.size() > 0));
        for (PlayerSummary.Sort sort : PlayerSummary.Sort.values()) {
            timed("joueurs " + sort, () -> StatsService.players(s, sort, 3, live()));
        }
        Views.PlayerPage page = StatsService.players(s, PlayerSummary.Sort.PLAYTIME, 0, live());
        timed("fiche joueur", () -> StatsService.playerCard(s, page.players().getFirst().uuid(), live()));
        for (Views.PerfMode mode : Views.PerfMode.values()) {
            timed("performance " + mode, () -> StatsService.performance(s, PARIS, mode, live()));
        }
    }
}
