package com.westcube.westcore.stats;

import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Mise en forme MiniMessage des valeurs de statistiques (console, lore des menus). */
public final class StatsText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private StatsText() {}

    /** "2 h 05", "42 min", "35 s". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " h " + String.format("%02d", m % 60);
    }

    public static String decimal(double v, int digits) {
        return String.format(Locale.FRANCE, "%." + digits + "f", v);
    }

    public static String percent(Double ratio) {
        return ratio == null ? "—" : decimal(ratio * 100, 1) + " %";
    }

    public static String integer(long v) {
        return String.format(Locale.FRANCE, "%,d", v).replace(' ', ' ').replace(' ', ' ');
    }

    /** Écart avec la veille : "<green>+3</green>", "<red>-12 %</red>"... (higherIsBetter colore le sens). */
    public static String delta(double today, Double yesterday, boolean higherIsBetter, boolean asDuration) {
        if (yesterday == null) return "<dark_gray>(hier : —)";
        double diff = today - yesterday;
        String color = diff == 0 ? "gray" : (diff > 0) == higherIsBetter ? "green" : "red";
        String value = asDuration ? duration(Math.round(Math.abs(diff))) : trim(Math.abs(diff));
        String sign = diff > 0 ? "+" : diff < 0 ? "-" : "±";
        return "<dark_gray>(hier : " + (asDuration ? duration(Math.round(yesterday)) : trim(yesterday))
                + ", <" + color + ">" + sign + value + "</" + color + ">)";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? integer((long) v) : decimal(v, 1);
    }

    /** "il y a 3 h", "il y a 2 j"... */
    public static String ago(long ms, long now) {
        long diff = Math.max(0, now - ms);
        long min = diff / 60_000;
        if (min < 1) return "à l'instant";
        if (min < 60) return "il y a " + min + " min";
        long h = min / 60;
        if (h < 24) return "il y a " + h + " h";
        long d = h / 24;
        return d < 60 ? "il y a " + d + " j" : "il y a " + (d / 30) + " mois";
    }

    public static String date(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).format(DATE);
    }

    public static String dateTime(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).format(DATE_TIME);
    }

    public static String quitReason(String reason) {
        if (reason == null) return "—";
        return switch (reason) {
            case "DISCONNECTED" -> "déconnexion";
            case "KICKED" -> "expulsé";
            case "TIMED_OUT" -> "timeout";
            case "ERRONEOUS_STATE" -> "erreur réseau";
            case "SHUTDOWN" -> "arrêt serveur";
            case "CRASH" -> "crash serveur";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }

    public static String level(StatsSettings.Level level) {
        return switch (level) {
            case GOOD -> "green";
            case WARN -> "gold";
            case BAD -> "red";
        };
    }

    /** Résumé du jour (console, et en jeu tant que le menu n'existe pas). */
    public static List<String> summary(Views.Overview o, StatsSettings settings) {
        DailyRecord t = o.today();
        DailyRecord y = o.yesterday();
        SnapshotRecord c = o.current();
        List<String> out = new ArrayList<>();
        out.add("<gradient:#ffd521:#e87e28><b>Statistiques du " + t.day().format(DATE) + "</b></gradient>");
        out.add(" <gray>Connectés : <white>" + o.online()
                + "</white>   Pic du jour : <white>" + t.peakOnline() + "</white> " + delta(t.peakOnline(), y == null ? null : (double) y.peakOnline(), true, false));
        out.add(" <gray>Joueurs uniques : <white>" + t.uniques() + "</white> " + delta(t.uniques(), y == null ? null : (double) y.uniques(), true, false));
        out.add(" <gray>Nouveaux : <white>" + t.newPlayers() + "</white> " + delta(t.newPlayers(), y == null ? null : (double) y.newPlayers(), true, false));
        out.add(" <gray>Durée médiane de session : <white>" + duration(t.medianSessionMs()) + "</white> "
                + delta(t.medianSessionMs(), y == null || y.sessions() == 0 ? null : (double) y.medianSessionMs(), true, true));
        out.add(" <gray>Temps de jeu cumulé : <white>" + duration(t.playMs()) + "</white> <dark_gray>(" + t.sessions() + " sessions)");
        String tps = level(settings.tps().level(c.tps1m()));
        String mspt = level(settings.mspt().level(c.msptAvg()));
        out.add(" <gray>TPS : <" + tps + ">" + decimal(c.tps1m(), 1) + "</" + tps + "> <dark_gray>/ " + decimal(c.tps5m(), 1)
                + " / " + decimal(c.tps15m(), 1) + "</dark_gray>   MSPT : <" + mspt + ">" + decimal(c.msptAvg(), 1) + " ms");
        out.add(" <gray>RAM : <white>" + integer(c.ramUsedMb()) + "</white> / " + integer(c.ramMaxMb()) + " Mo   Chunks : <white>"
                + integer(c.chunks()) + "</white>   Entités : <white>" + integer(c.entities()));
        return out;
    }
}
