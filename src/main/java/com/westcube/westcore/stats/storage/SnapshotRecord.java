package com.westcube.westcore.stats.storage;

/**
 * État du serveur à un instant. msptMax = pire tick depuis le snapshot précédent.
 * pingAvg null si personne n'est connecté.
 */
public record SnapshotRecord(
        String serverId,
        long ts,
        int online,
        double tps1m,
        double tps5m,
        double tps15m,
        double msptAvg,
        double msptMax,
        long ramUsedMb,
        long ramMaxMb,
        int chunks,
        int entities,
        Double pingAvg) {}
