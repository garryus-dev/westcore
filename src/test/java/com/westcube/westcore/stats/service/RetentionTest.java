package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.storage.PlayerRecord;
import com.westcube.westcore.stats.storage.SessionRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RetentionTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final long MIN = 60_000L;
    static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    static final long ONE_SHOT = 10 * MIN;

    static long at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(PARIS).toInstant().toEpochMilli();
    }

    static SessionRecord s(UUID uuid, long start, long minutes) {
        return new SessionRecord(0, "t", uuid, "x", start, start + minutes * MIN, null, null, null, null, null, 0L, "DISCONNECTED");
    }

    static Retention.Cohort cohort(Map<UUID, List<SessionRecord>> sessions, LocalDate today) {
        List<PlayerRecord> members = sessions.entrySet().stream()
                .map(e -> new PlayerRecord("t", e.getKey(), "x", e.getValue().getFirst().startMs(), 0)).toList();
        long now = at(today, 12);
        return Retention.cohort(MONDAY, members, sessions, PARIS, today, now, ONE_SHOT);
    }

    @Test
    void notMeasurableUntilWindowEnds() {
        UUID a = UUID.randomUUID();
        // Première connexion lundi 23h, retour mardi 0h30 (jour civil suivant)
        Map<UUID, List<SessionRecord>> sessions = Map.of(a, List.of(
                s(a, at(MONDAY, 23), 20), s(a, at(MONDAY.plusDays(1), 0) + 30 * MIN, 20)));

        Retention.Cohort tuesday = cohort(sessions, MONDAY.plusDays(1));
        assertNull(tuesday.d1().value()); // J+1 pas terminé

        Retention.Cohort wednesday = cohort(sessions, MONDAY.plusDays(2));
        assertEquals(1.0, wednesday.d1().value());
        assertNull(wednesday.d7().value());

        Retention.Cohort later = cohort(sessions, MONDAY.plusDays(40));
        assertEquals(1.0, later.d7().value());
        assertEquals(1.0, later.d30().value());
    }

    @Test
    void returnWindows() {
        UUID day3 = UUID.randomUUID(), day20 = UUID.randomUUID(), never = UUID.randomUUID();
        Map<UUID, List<SessionRecord>> sessions = Map.of(
                day3, List.of(s(day3, at(MONDAY, 10), 60), s(day3, at(MONDAY.plusDays(3), 10), 60)),
                day20, List.of(s(day20, at(MONDAY, 10), 60), s(day20, at(MONDAY.plusDays(20), 10), 60)),
                never, List.of(s(never, at(MONDAY, 10), 60)));
        Retention.Cohort c = cohort(sessions, MONDAY.plusDays(60));
        assertEquals(3, c.size());
        assertEquals(new Retention.Rate(0, 3), c.d1());
        assertEquals(new Retention.Rate(1, 3), c.d7());
        assertEquals(new Retention.Rate(2, 3), c.d30());
    }

    @Test
    void oneShot() {
        UUID quick = UUID.randomUUID(), quickBack = UUID.randomUUID(), longStay = UUID.randomUUID();
        Map<UUID, List<SessionRecord>> sessions = Map.of(
                quick, List.of(s(quick, at(MONDAY, 10), 5)),
                quickBack, List.of(s(quickBack, at(MONDAY, 10), 5), s(quickBack, at(MONDAY.plusDays(6), 10), 5)),
                longStay, List.of(s(longStay, at(MONDAY, 10), 45)));
        assertEquals(new Retention.Rate(1, 3), cohort(sessions, MONDAY.plusDays(10)).oneShot());
        // Moins de 7 jours après la première session : pas encore mesurable
        assertNull(cohort(sessions, MONDAY.plusDays(3)).oneShot().value());
    }
}
