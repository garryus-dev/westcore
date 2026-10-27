package com.westcube.westcore.stats.storage;

import java.util.UUID;

/**
 * Une session de jeu (join -> quit). Horodatages en millisecondes UTC.
 * endMs null = session ouverte ; afkMs null = temps AFK non mesuré (module afk désactivé).
 * quitReason : raison Paper (DISCONNECTED, KICKED, TIMED_OUT, ERRONEOUS_STATE),
 * ou SHUTDOWN (arrêt du serveur), CRASH (clôturée au démarrage suivant).
 */
public record SessionRecord(
        long id,
        String serverId,
        UUID uuid,
        String name,
        long startMs,
        Long endMs,
        String hostname,
        String clientVersion,
        Integer protocol,
        String clientBrand,
        String locale,
        Long afkMs,
        String quitReason) {

    public static final String SHUTDOWN = "SHUTDOWN";
    public static final String CRASH = "CRASH";
    public static final String TIMED_OUT = "TIMED_OUT";

    public boolean isOpen() {
        return endMs == null;
    }

    /** Durée, en prenant "now" comme fin pour une session ouverte. */
    public long durationMs(long now) {
        return Math.max(0, (endMs != null ? endMs : now) - startMs);
    }
}
