package com.westcube.westcore.stats;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;

/** Section "stats" de config.yml, lue une fois au démarrage du module. */
public record StatsSettings(
        String serverId,
        ZoneId zone,
        int snapshotIntervalMinutes,
        LocalTime aggregationTime,
        int rawRetentionDays,
        boolean countIdleTime,
        int oneShotMinutes,
        int cacheSeconds,
        Threshold tps,
        Threshold mspt,
        Threshold retentionD1,
        Threshold retentionD7,
        Threshold retentionD30,
        boolean exportAnonymize,
        int exportKeepDays,
        int exportCooldownSeconds) {

    public enum Level { GOOD, WARN, BAD }

    /** higherIsBetter : TPS, rétention ; sinon MSPT (plus bas = mieux). */
    public record Threshold(double good, double warn, boolean higherIsBetter) {

        public Level level(double value) {
            if (higherIsBetter) {
                if (value >= good) return Level.GOOD;
                return value >= warn ? Level.WARN : Level.BAD;
            }
            if (value <= good) return Level.GOOD;
            return value <= warn ? Level.WARN : Level.BAD;
        }
    }

    public static StatsSettings load(ConfigurationSection cfg, Logger logger) {
        String serverId = cfg.getString("server-id", "westcube").trim();
        if (serverId.isEmpty()) serverId = "westcube";

        ZoneId zone;
        try {
            zone = ZoneId.of(cfg.getString("timezone", "Europe/Paris"));
        } catch (DateTimeException e) {
            logger.warning("stats.timezone invalide, Europe/Paris utilisé.");
            zone = ZoneId.of("Europe/Paris");
        }

        LocalTime aggregation;
        try {
            aggregation = LocalTime.parse(cfg.getString("aggregation-time", "04:00"));
        } catch (DateTimeParseException e) {
            logger.warning("stats.aggregation-time invalide (format HH:mm), 04:00 utilisé.");
            aggregation = LocalTime.of(4, 0);
        }

        return new StatsSettings(
                serverId,
                zone,
                Math.max(1, cfg.getInt("snapshot-interval-minutes", 5)),
                aggregation,
                Math.max(8, cfg.getInt("raw-retention-days", 90)),
                cfg.getBoolean("afk.count-idle-time", true),
                Math.max(1, cfg.getInt("one-shot-minutes", 10)),
                Math.max(0, cfg.getInt("cache-seconds", 60)),
                threshold(cfg, "thresholds.tps", 19.5, 17.0, true),
                threshold(cfg, "thresholds.mspt", 40, 50, false),
                threshold(cfg, "thresholds.retention.d1", 0.40, 0.25, true),
                threshold(cfg, "thresholds.retention.d7", 0.20, 0.10, true),
                threshold(cfg, "thresholds.retention.d30", 0.10, 0.05, true),
                cfg.getBoolean("export.anonymize", false),
                Math.max(1, cfg.getInt("export.keep-days", 7)),
                Math.max(0, cfg.getInt("export.cooldown-seconds", 60)));
    }

    private static Threshold threshold(ConfigurationSection cfg, String path, double good, double warn, boolean higher) {
        return new Threshold(cfg.getDouble(path + ".good", good), cfg.getDouble(path + ".warn", warn), higher);
    }
}
