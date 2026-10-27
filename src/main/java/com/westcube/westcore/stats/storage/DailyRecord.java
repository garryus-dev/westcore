package com.westcube.westcore.stats.storage;

import java.time.LocalDate;

/**
 * Agrégat d'une journée civile (fuseau configuré).
 * Fréquentation calculée depuis les sessions (toujours disponibles) ;
 * performance depuis les snapshots (null si plus de données brutes pour ce jour).
 */
public record DailyRecord(
        String serverId,
        LocalDate day,
        int uniques,
        int newPlayers,
        int sessions,
        int peakOnline,
        long playMs,
        Long afkMs,
        long medianSessionMs,
        long avgSessionMs,
        int timeouts,
        Double tpsAvg,
        Double tpsMin,
        Double msptAvg,
        Double msptMax,
        Long ramUsedAvgMb,
        Long ramMaxMb,
        Integer chunksAvg,
        Integer entitiesAvg,
        Double pingAvg,
        long computedAt) {}
