package com.westcube.westcore.stats.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStatsStorageTest {

    private static final long MIN = 60_000L;

    @TempDir
    File dir;

    private final AtomicBoolean mainThread = new AtomicBoolean(false);
    private SqliteStatsStorage storage;

    @BeforeEach
    void open() {
        storage = new SqliteStatsStorage(new File(dir, "stats.db"), "test", mainThread::get);
        storage.open();
    }

    @AfterEach
    void close() {
        storage.close();
    }

    private static SnapshotRecord snapshot(long ts, int online) {
        return new SnapshotRecord("test", ts, online, 20, 20, 20, 12.5, 48.0, 2048, 8192, 1500, 3000, online > 0 ? 42.0 : null);
    }

    @Test
    void refusesMainThread() {
        mainThread.set(true);
        assertThrows(IllegalStateException.class, () -> storage.countPlayers());
    }

    @Test
    void reopeningKeepsSchemaAndData() {
        storage.upsertPlayer(UUID.randomUUID(), "Bob", 1000, 1000);
        storage.close();
        storage = new SqliteStatsStorage(new File(dir, "stats.db"), "test", mainThread::get);
        storage.open();
        assertEquals(1, storage.countPlayers());
        assertEquals("1", storage.meta("schema_version").orElseThrow());
    }

    @Test
    void sessionLifecycle() {
        UUID uuid = UUID.randomUUID();
        long id = storage.openSession(uuid, "Bob", 1000, "play.westcube.fr", "1.21.11", 774, null, null);
        storage.updateSessionClient(id, "vanilla", "fr_fr");
        storage.updateSessionClient(id, null, "en_us");
        storage.updateSessionAfk(Map.of(id, 5 * MIN));

        SessionRecord open = storage.sessionsOf(uuid, 10).getFirst();
        assertTrue(open.isOpen());
        assertEquals("vanilla", open.clientBrand());
        assertEquals("en_us", open.locale());
        assertEquals(5 * MIN, open.afkMs());

        // AFK jamais supérieur à la durée de la session
        storage.closeSession(id, 1000 + 3 * MIN, 10 * MIN, "DISCONNECTED");
        SessionRecord closed = storage.sessionsOf(uuid, 10).getFirst();
        assertEquals(1000 + 3 * MIN, closed.endMs());
        assertEquals(3 * MIN, closed.afkMs());
        assertEquals("DISCONNECTED", closed.quitReason());

        // Une session déjà fermée ne se referme pas
        storage.closeSession(id, 1000 + 60 * MIN, null, "KICKED");
        assertEquals("DISCONNECTED", storage.sessionsOf(uuid, 10).getFirst().quitReason());
    }

    @Test
    void afkNullMeansNotMeasured() {
        UUID uuid = UUID.randomUUID();
        long id = storage.openSession(uuid, "Bob", 0, null, null, null, null, null);
        storage.closeSession(id, 10 * MIN, null, "DISCONNECTED");
        assertNull(storage.sessionsOf(uuid, 1).getFirst().afkMs());
    }

    @Test
    void orphansClosedAtLastSnapshotNeverBeforeStart() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.upsertPlayer(a, "A", 0, 0);
        storage.upsertPlayer(b, "B", 0, 40 * MIN);
        long sa = storage.openSession(a, "A", 0, null, null, null, null, null);
        storage.insertSnapshot(snapshot(30 * MIN, 1));
        long sb = storage.openSession(b, "B", 40 * MIN, null, null, null, null, null);
        storage.updateSessionAfk(Map.of(sa, 45 * MIN));

        assertEquals(2, storage.closeOrphanSessions(SessionRecord.CRASH));

        SessionRecord ra = storage.sessionsOf(a, 1).getFirst();
        assertEquals(30 * MIN, ra.endMs());
        assertEquals(30 * MIN, ra.afkMs());
        assertEquals(SessionRecord.CRASH, ra.quitReason());
        // Ouverte après le dernier snapshot : durée nulle plutôt que négative
        assertEquals(40 * MIN, storage.sessionsOf(b, 1).getFirst().endMs());
        assertEquals(30 * MIN, storage.player(a).orElseThrow().lastSeen());

        assertEquals(0, storage.closeOrphanSessions(SessionRecord.CRASH));
        assertNotEquals(sa, sb);
    }

    @Test
    void firstSeenNeverMovesForward() {
        UUID uuid = UUID.randomUUID();
        storage.upsertPlayer(uuid, "Old", 1000, 2000);
        storage.upsertPlayer(uuid, "New", 5000, 6000);
        PlayerRecord p = storage.player(uuid).orElseThrow();
        assertEquals("New", p.name());
        assertEquals(1000, p.firstSeen());
        assertEquals(6000, p.lastSeen());
    }

    @Test
    void overlappingSessions() {
        UUID uuid = UUID.randomUUID();
        long s1 = storage.openSession(uuid, "Bob", 0, null, null, null, null, null);
        storage.closeSession(s1, 10 * MIN, 0L, "DISCONNECTED");
        storage.openSession(uuid, "Bob", 20 * MIN, null, null, null, null, null);

        assertEquals(1, storage.sessionsOverlapping(5 * MIN, 15 * MIN).size());
        assertEquals(0, storage.sessionsOverlapping(10 * MIN, 20 * MIN).size());
        assertEquals(1, storage.sessionsOverlapping(100 * MIN, 200 * MIN).size()); // session ouverte
        assertEquals(0L, storage.firstSessionStart().orElseThrow());
    }

    @Test
    void playerSummariesSorted() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        storage.upsertPlayer(a, "Alice", 0, 100 * MIN);
        storage.upsertPlayer(b, "Bob", 50 * MIN, 60 * MIN);
        storage.upsertPlayer(c, "Carl", 70 * MIN, 70 * MIN);
        long s = storage.openSession(a, "Alice", 10 * MIN, null, null, null, null, null);
        storage.closeSession(s, 40 * MIN, 0L, "DISCONNECTED");
        s = storage.openSession(b, "Bob", 50 * MIN, null, null, null, null, null);
        storage.closeSession(s, 55 * MIN, 0L, "DISCONNECTED");
        s = storage.openSession(b, "Bob", 56 * MIN, null, null, null, null, null);
        storage.closeSession(s, 60 * MIN, 0L, "DISCONNECTED");
        storage.openSession(c, "Carl", 70 * MIN, null, null, null, null, null); // en ligne

        long now = 80 * MIN;
        List<PlayerSummary> byTime = storage.playerSummaries(0, now, PlayerSummary.Sort.PLAYTIME, 0, 10);
        // Alice 30 min, Carl 10 min (session en cours jusqu'à "now"), Bob 5 + 4 min
        assertEquals(List.of("Alice", "Carl", "Bob"), byTime.stream().map(PlayerSummary::name).toList());
        assertEquals(30 * MIN, byTime.get(0).playMs());
        assertEquals(10 * MIN, byTime.get(1).playMs());
        assertEquals(9 * MIN, byTime.get(2).playMs());

        assertEquals("Bob", storage.playerSummaries(0, now, PlayerSummary.Sort.SESSIONS, 0, 10).getFirst().name());
        assertEquals("Carl", storage.playerSummaries(0, now, PlayerSummary.Sort.NEWEST, 0, 10).getFirst().name());
        assertEquals("Alice", storage.playerSummaries(0, now, PlayerSummary.Sort.LAST_SEEN, 0, 10).getFirst().name());

        // Période : seules les sessions commencées depuis "since" comptent
        PlayerSummary alice = storage.playerSummaries(45 * MIN, now, PlayerSummary.Sort.NEWEST, 0, 10).getLast();
        assertEquals(0, alice.sessions());
        assertEquals(0, alice.playMs());

        assertEquals(1, storage.playerSummaries(0, now, PlayerSummary.Sort.PLAYTIME, 1, 1).size());
        assertEquals(3, storage.countPlayers());
        assertEquals(2, storage.playersFirstSeenBetween(50 * MIN, 80 * MIN).size());
    }

    @Test
    void snapshotsAndPurge() {
        for (int i = 0; i < 10; i++) storage.insertSnapshot(snapshot(i * 5 * MIN, i));
        assertEquals(45 * MIN, storage.lastSnapshot().orElseThrow().ts());
        assertNull(storage.snapshotsBetween(0, 1).getFirst().pingAvg());
        assertEquals(4, storage.snapshotsBetween(10 * MIN, 30 * MIN).size());
        assertEquals(5, storage.purgeSnapshotsBefore(25 * MIN));
        assertEquals(5, storage.snapshotsBetween(0, 100 * MIN).size());
    }

    @Test
    void dailyRoundTrip() {
        LocalDate day = LocalDate.of(2026, 10, 11);
        DailyRecord d = new DailyRecord("test", day, 12, 3, 20, 7, 9_000_000, null, 1_200_000, 1_500_000, 1,
                19.8, 17.2, 21.0, 180.5, 4096L, 8192L, 1500, 3000, null, 123);
        storage.upsertDaily(d);
        storage.upsertDaily(new DailyRecord("test", day.minusDays(1), 1, 0, 1, 1, 1, 0L, 1, 1, 0,
                null, null, null, null, null, null, null, null, null, 1));

        assertEquals(d, storage.daily(day, day).getFirst());
        assertEquals(2, storage.daily(day.minusDays(5), day).size());
        assertEquals(day, storage.lastDailyDay().orElseThrow());
        storage.deleteDaily();
        assertTrue(storage.lastDailyDay().isEmpty());
    }

    @Test
    void serverIdsAreIsolated() {
        storage.upsertPlayer(UUID.randomUUID(), "Bob", 0, 0);
        storage.close();
        storage = new SqliteStatsStorage(new File(dir, "stats.db"), "autre", mainThread::get);
        storage.open();
        assertEquals(0, storage.countPlayers());
    }
}
