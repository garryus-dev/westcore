package com.westcube.westcore.stats.service;

import com.westcube.westcore.stats.storage.PlayerRecord;
import com.westcube.westcore.stats.storage.SessionRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rétention d'une cohorte (joueurs dont la première connexion tombe dans une même semaine).
 *   J1  : une connexion le jour civil suivant la première connexion.
 *   J7  : au moins une connexion entre J+1 et J+7 (inclus) ; J30 : entre J+1 et J+30.
 *   One-shot : première session < seuil et aucune autre connexion dans les 7 jours qui suivent.
 * Un joueur n'est compté que si sa fenêtre est entièrement écoulée ("mesuré").
 */
public final class Retention {

    private static final long DAY_MS = 86_400_000L;

    private Retention() {}

    /** hits / measured ; value() null si personne n'est encore mesurable. */
    public record Rate(int hits, int measured) {
        public Double value() {
            return measured == 0 ? null : (double) hits / measured;
        }
    }

    public record Cohort(LocalDate weekStart, int size, Rate d1, Rate d7, Rate d30, Rate oneShot) {}

    /**
     * @param sessionsByPlayer sessions de chaque membre, triées par début croissant
     * @param today            jour courant dans le fuseau
     */
    public static Cohort cohort(LocalDate weekStart, List<PlayerRecord> members,
                                Map<UUID, List<SessionRecord>> sessionsByPlayer,
                                ZoneId zone, LocalDate today, long now, long oneShotMs) {
        int d1 = 0, d1m = 0, d7 = 0, d7m = 0, d30 = 0, d30m = 0, os = 0, osm = 0;
        for (PlayerRecord p : members) {
            List<SessionRecord> sessions = sessionsByPlayer.getOrDefault(p.uuid(), List.of());
            LocalDate first = day(p.firstSeen(), zone);

            if (today.isAfter(first.plusDays(1))) {
                d1m++;
                if (returnedBetween(sessions, zone, first.plusDays(1), first.plusDays(1))) d1++;
            }
            if (today.isAfter(first.plusDays(7))) {
                d7m++;
                if (returnedBetween(sessions, zone, first.plusDays(1), first.plusDays(7))) d7++;
            }
            if (today.isAfter(first.plusDays(30))) {
                d30m++;
                if (returnedBetween(sessions, zone, first.plusDays(1), first.plusDays(30))) d30++;
            }

            if (!sessions.isEmpty()) {
                SessionRecord firstSession = sessions.getFirst();
                long windowEnd = firstSession.startMs() + 7 * DAY_MS;
                if (now >= windowEnd) {
                    osm++;
                    boolean shortFirst = firstSession.durationMs(now) < oneShotMs;
                    boolean cameBack = sessions.stream().skip(1)
                            .anyMatch(s -> s.startMs() > firstSession.startMs() && s.startMs() <= windowEnd);
                    if (shortFirst && !cameBack) os++;
                }
            }
        }
        return new Cohort(weekStart, members.size(), new Rate(d1, d1m), new Rate(d7, d7m), new Rate(d30, d30m), new Rate(os, osm));
    }

    private static boolean returnedBetween(List<SessionRecord> sessions, ZoneId zone, LocalDate from, LocalDate to) {
        for (SessionRecord s : sessions) {
            LocalDate d = day(s.startMs(), zone);
            if (!d.isBefore(from) && !d.isAfter(to)) return true;
        }
        return false;
    }

    static LocalDate day(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }
}
