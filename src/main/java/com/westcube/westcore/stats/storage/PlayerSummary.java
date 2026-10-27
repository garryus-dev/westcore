package com.westcube.westcore.stats.storage;

import java.util.UUID;

/** Ligne de la liste des joueurs : activité sur une période (sessions commencées depuis "since"). */
public record PlayerSummary(UUID uuid, String name, long firstSeen, long lastSeen, int sessions, long playMs) {

    public enum Sort { PLAYTIME, LAST_SEEN, SESSIONS, NEWEST }
}
