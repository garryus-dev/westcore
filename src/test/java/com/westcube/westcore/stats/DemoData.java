package com.westcube.westcore.stats;

import com.westcube.westcore.stats.aggregate.DailyAggregator;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import com.westcube.westcore.stats.storage.SqliteStatsStorage;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Génère une base de démonstration réaliste (joueurs, sessions, relevés toutes les 5 min).
 * Lancement : mvn -q test-compile exec:java ... ou directement depuis les tests (StatsLoadTest).
 */
public final class DemoData {

    private static final long MIN = 60_000L;
    private static final String[] BRANDS = {"vanilla", "fabric", "lunarclient:v2", "forge", "badlion"};
    private static final String[] LOCALES = {"fr_fr", "fr_fr", "fr_fr", "fr_ca", "en_us", "fr_be"};
    private static final String[][] VERSIONS = {{"1.21.11", "774"}, {"1.21.10", "773"}, {"1.21.4", "769"}, {"1.20.1", "763"}};

    private DemoData() {}

    /** Remplit storage avec "days" jours d'activité se terminant à "now". Renvoie le nombre de sessions. */
    public static int generate(SqliteStatsStorage s, ZoneId zone, int days, long now, long seed) {
        Random rnd = new Random(seed);
        LocalDate today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone);
        LocalDate first = today.minusDays(days - 1);

        // Joueurs : une partie est déjà là au début, les autres arrivent au fil des jours
        List<UUID> players = new ArrayList<>();
        List<Long> arrival = new ArrayList<>();
        List<Double> assiduity = new ArrayList<>();
        for (int i = 0; i < 320; i++) {
            players.add(new UUID(seed, i));
            boolean veteran = i < 60;
            long arrivedAt = veteran
                    ? first.minusDays(rnd.nextInt(300) + 1).atStartOfDay(zone).toInstant().toEpochMilli()
                    : first.plusDays(rnd.nextInt(days)).atTime(12 + rnd.nextInt(10), rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli();
            arrival.add(arrivedAt);
            // Beaucoup de joueurs ne reviennent presque pas, quelques réguliers
            double r = rnd.nextDouble();
            assiduity.add(r < 0.35 ? 0.0 : r < 0.75 ? 0.08 : r < 0.92 ? 0.3 : 0.75);
        }

        int count = 0;
        Map<Integer, Integer> hourWeight = Map.ofEntries(
                Map.entry(0, 3), Map.entry(1, 1), Map.entry(2, 1), Map.entry(8, 1), Map.entry(10, 2), Map.entry(12, 3),
                Map.entry(14, 4), Map.entry(16, 5), Map.entry(17, 6), Map.entry(18, 8), Map.entry(19, 9), Map.entry(20, 10),
                Map.entry(21, 10), Map.entry(22, 7), Map.entry(23, 5));
        for (int p = 0; p < players.size(); p++) {
            UUID uuid = players.get(p);
            String name = "Joueur" + p;
            long arrivedAt = arrival.get(p);
            s.upsertPlayer(uuid, name, arrivedAt, arrivedAt);
            String[] version = VERSIONS[rnd.nextInt(VERSIONS.length)];
            String brand = BRANDS[rnd.nextInt(BRANDS.length)];
            String locale = LOCALES[rnd.nextInt(LOCALES.length)];

            for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
                long dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli();
                boolean arrivalDay = arrivedAt >= dayStart && arrivedAt < dayStart + 1440 * MIN;
                if (arrivedAt >= dayStart + 1440 * MIN) continue;
                double chance = assiduity.get(p) * (d.getDayOfWeek().getValue() >= 6 ? 1.4 : 1.0);
                if (!arrivalDay && rnd.nextDouble() >= chance) continue;

                long start;
                if (arrivalDay) {
                    start = arrivedAt;
                } else {
                    int hour = pickHour(rnd, hourWeight);
                    start = d.atTime(hour, rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli();
                }
                long length = arrivalDay && assiduity.get(p) == 0.0
                        ? (2 + rnd.nextInt(9)) * MIN
                        : (long) ((5 + rnd.nextExponential() * 55) * MIN);
                long end = start + length;
                if (start >= now) continue;
                long id = s.openSession(uuid, name, start, "play.westcube.fr", version[0], Integer.parseInt(version[1]), brand, locale);
                if (end < now) {
                    String reason = rnd.nextDouble() < 0.03 ? "TIMED_OUT" : rnd.nextDouble() < 0.05 ? "KICKED" : "DISCONNECTED";
                    s.closeSession(id, end, (long) (length * rnd.nextDouble() * 0.3), reason);
                    s.upsertPlayer(uuid, name, arrivedAt, end);
                }
                count++;
            }
        }

        // Relevés toutes les 5 min, MSPT qui suit l'activité du soir
        for (long ts = first.atStartOfDay(zone).toInstant().toEpochMilli(); ts < now; ts += 5 * MIN) {
            int hour = java.time.Instant.ofEpochMilli(ts).atZone(zone).getHour();
            int online = hourWeight.getOrDefault(hour, 1) + rnd.nextInt(4);
            double mspt = 8 + online * 1.8 + rnd.nextDouble() * 6;
            double worst = mspt + rnd.nextExponential() * 25;
            double tps = Math.min(20, 1000 / Math.max(50, mspt));
            s.insertSnapshot(new SnapshotRecord(s.serverId(), ts, online, tps, Math.min(20, tps + 0.1), 20,
                    mspt, worst, 2500 + rnd.nextInt(2500), 8192, 1500 + online * 120, 2500 + online * 300, 40.0 + rnd.nextInt(60)));
        }
        DailyAggregator.catchUp(s, zone, now);
        return count;
    }

    private static int pickHour(Random rnd, Map<Integer, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int r = rnd.nextInt(total);
        for (Map.Entry<Integer, Integer> e : weights.entrySet()) {
            r -= e.getValue();
            if (r < 0) return e.getKey();
        }
        return 20;
    }

    /** java DemoData <fichier.db> : crée une base de démonstration de 90 jours. */
    public static void main(String[] args) {
        File file = new File(args.length > 0 ? args[0] : "stats-demo.db");
        if (file.exists()) throw new IllegalStateException(file + " existe déjà");
        SqliteStatsStorage s = new SqliteStatsStorage(file, args.length > 1 ? args[1] : "westcube", () -> false);
        s.open();
        int n = generate(s, ZoneId.of("Europe/Paris"), 90, System.currentTimeMillis(), 42);
        s.close();
        System.out.println(n + " sessions générées dans " + file.getAbsolutePath());
    }
}
