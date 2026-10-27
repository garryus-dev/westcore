package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CalcTest {

    static final long MIN = 60_000L;
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    static SessionRecord session(UUID uuid, long start, Long end, Long afk, String reason) {
        return new SessionRecord(0, "test", uuid, "x", start, end, null, null, null, null, null, afk, reason);
    }

    static SessionRecord session(UUID uuid, long start, Long end) {
        return session(uuid, start, end, null, "DISCONNECTED");
    }

    @Test
    void medianOddEvenEmpty() {
        assertEquals(0, Calc.median(new long[0]));
        assertEquals(5, Calc.median(new long[]{9, 1, 5}));
        assertEquals(4, Calc.median(new long[]{1, 3, 5, 100}));
    }

    @Test
    void clipsSessionsToInterval() {
        // Session de 50 à 150 min, intervalle 100 à 200 : 50 min comptées, mais durée complète pour la médiane
        List<SessionRecord> s = List.of(session(A, 50 * MIN, 150 * MIN));
        Calc.Activity a = Calc.activity(s, 100 * MIN, 200 * MIN, 1000 * MIN);
        assertEquals(1, a.uniques());
        assertEquals(50 * MIN, a.playMs());
        assertEquals(0, a.sessionsStarted());
        assertEquals(1, a.peakOnline());

        Calc.Activity before = Calc.activity(s, 0, 100 * MIN, 1000 * MIN);
        assertEquals(1, before.sessionsStarted());
        assertEquals(100 * MIN, before.medianSessionMs());
    }

    @Test
    void openSessionsEndNow() {
        Calc.Activity a = Calc.activity(List.of(session(A, 0, null)), 0, 100 * MIN, 30 * MIN);
        assertEquals(30 * MIN, a.playMs());
        assertEquals(30 * MIN, a.medianSessionMs());
    }

    @Test
    void peakIgnoresInstantReconnect() {
        List<SessionRecord> s = List.of(
                session(A, 0, 10 * MIN),
                session(A, 10 * MIN, 20 * MIN),   // reconnexion au même instant
                session(B, 5 * MIN, 15 * MIN),
                session(C, 30 * MIN, 40 * MIN));
        Calc.Activity a = Calc.activity(s, 0, 60 * MIN, 60 * MIN);
        assertEquals(2, a.peakOnline());
        assertEquals(3, a.uniques());
        assertEquals(4, a.sessionsStarted());
        assertEquals(10 * MIN, a.medianSessionMs());
    }

    @Test
    void timeoutsAndAfk() {
        List<SessionRecord> s = List.of(
                session(A, 0, 100 * MIN, 40 * MIN, "TIMED_OUT"),
                session(B, 0, 10 * MIN, null, "TIMED_OUT"));
        // Moitié de la session A dans l'intervalle -> moitié de son AFK
        Calc.Activity a = Calc.activity(s, 50 * MIN, 200 * MIN, 200 * MIN);
        assertEquals(20 * MIN, a.afkMs());
        assertEquals(1, a.timeouts());
        assertEquals(1, a.uniques());

        assertNull(Calc.activity(List.of(session(B, 0, 10 * MIN)), 0, 20 * MIN, 20 * MIN).afkMs());
    }

    @Test
    void zeroLengthSessionStillCounts() {
        Calc.Activity a = Calc.activity(List.of(session(A, 10 * MIN, 10 * MIN)), 0, 20 * MIN, 20 * MIN);
        assertEquals(1, a.uniques());
        assertEquals(1, a.sessionsStarted());
        assertEquals(0, a.playMs());
    }

    @Test
    void perfAggregates() {
        assertNull(Calc.perf(List.of()));
        Calc.Perf p = Calc.perf(List.of(
                new SnapshotRecord("t", 0, 2, 20, 20, 20, 10, 30, 1000, 4000, 100, 200, 50.0),
                new SnapshotRecord("t", 1, 0, 18, 19, 19, 30, 120, 3000, 4000, 300, 400, null)));
        assertEquals(19, p.tpsAvg(), 1e-9);
        assertEquals(18, p.tpsMin(), 1e-9);
        assertEquals(20, p.msptAvg(), 1e-9);
        assertEquals(120, p.msptMax(), 1e-9);
        assertEquals(2000, p.ramUsedAvgMb());
        assertEquals(50.0, p.pingAvg());
        assertEquals(2, p.onlineMax());
    }
}
