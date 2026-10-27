package com.westcube.westcore.stats.export;

import com.westcube.westcore.stats.service.Calc;
import com.westcube.westcore.stats.service.Retention;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import org.dhatim.fastexcel.Workbook;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Classeur .xlsx (FastExcel, écriture en flux) : Résumé, Journalier, Horaire, Joueurs, Rétention, Performance.
 * Écrit dans un fichier temporaire puis renommé : un fichier visible est toujours complet.
 */
public final class StatsExporter {

    private StatsExporter() {}

    public static String fileName(ExportData data) {
        return "stats_" + data.to() + "_" + data.period().id + ".xlsx";
    }

    public static File write(ExportData data, File dir, boolean anonymize) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Dossier impossible à créer : " + dir);
        File target = new File(dir, fileName(data));
        File tmp = new File(dir, target.getName() + ".tmp");
        Map<UUID, String> names = anonymize ? pseudonyms(data) : null;

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp.toPath()))) {
            Workbook wb = new Workbook(out, "WestCore", "1.0");
            wb.properties().setTitle("Statistiques WestCube — " + data.period().label);
            summary(wb, data);
            daily(wb, data);
            hourly(wb, data);
            players(wb, data, names);
            retention(wb, data);
            performance(wb, data);
            wb.finish();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** "Joueur #0042" : numéro stable dans le fichier, attribué par ordre d'arrivée sur le serveur. */
    private static Map<UUID, String> pseudonyms(ExportData data) {
        List<ExportData.PlayerRow> byArrival = data.players().stream()
                .sorted(Comparator.comparingLong(ExportData.PlayerRow::firstSeen).thenComparing(p -> p.uuid().toString()))
                .toList();
        Map<UUID, String> out = new HashMap<>();
        for (int i = 0; i < byArrival.size(); i++) out.put(byArrival.get(i).uuid(), String.format("Joueur #%04d", i + 1));
        return out;
    }

    private static LocalDateTime local(long ms, ExportData data) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), data.zone());
    }

    // ------------------------------------------------------------- onglets

    private static void summary(Workbook wb, ExportData d) {
        ExportData.Summary s = d.summary();
        Calc.Perf p = s.perf();
        SheetWriter w = new SheetWriter(wb, "Résumé").header("Indicateur", 38, "Valeur", 20, "Détail", 48);
        w.row().text("Période").text(d.period().label).text(null);
        w.row().text("Du").date(d.from()).text(null);
        w.row().text("Au").date(d.to()).text("jour de l'export inclus");
        w.row().text("Généré le").dateTime(local(d.generatedAt(), d)).text("fuseau " + d.zone().getId());
        w.row().text("Serveur").text(d.serverId()).text(null);
        w.row().text("Joueurs uniques").integer(s.uniques()).text(null);
        w.row().text("Nouveaux joueurs").integer(s.newPlayers()).text("première connexion sur la période");
        w.row().text("Joueurs uniques par jour (moyenne)").decimal(s.avgUniquesPerDay(), "0.0").text(null);
        w.row().text("Sessions").integer(s.sessions()).text("sessions commencées sur la période");
        w.row().text("Temps de jeu cumulé").duration(s.playMs()).text("heures:minutes");
        w.row().text("Temps AFK cumulé").duration(s.afkMs()).text(s.afkMs() == null ? "non mesuré" : null);
        w.row().text("Durée médiane de session").duration(s.medianMs()).text(null);
        w.row().text("Durée moyenne de session").duration(s.avgMs()).text(null);
        w.row().text("Pic de joueurs simultanés").integer(s.peak()).text(s.peakDay() == null ? null : "le " + s.peakDay());
        w.row().text("Rétention J1").percent(s.d1().value()).text(rateDetail(s.d1()));
        w.row().text("Rétention J7").percent(s.d7().value()).text(rateDetail(s.d7()));
        w.row().text("Rétention J30").percent(s.d30().value()).text(rateDetail(s.d30()));
        w.row().text("One-shot").percent(s.oneShot().value()).text(rateDetail(s.oneShot()));
        w.row().text("TPS moyen").decimal(p == null ? null : p.tpsAvg(), "0.00").text(null);
        w.row().text("TPS le plus bas").decimal(p == null ? null : p.tpsMin(), "0.00").text(null);
        w.row().text("MSPT moyen (ms)").decimal(p == null ? null : p.msptAvg(), "0.0").text(null);
        w.row().text("Pire tick (ms)").decimal(p == null ? null : p.msptMax(), "0.0").text(null);
        w.row().text("Timeouts").integer(s.timeouts()).text("déconnexions par perte de connexion");
        w.finish();
    }

    private static String rateDetail(Retention.Rate r) {
        return r.measured() == 0 ? "pas encore mesurable" : r.hits() + " / " + r.measured() + " joueur(s) mesuré(s)";
    }

    private static void daily(Workbook wb, ExportData d) {
        SheetWriter w = new SheetWriter(wb, "Journalier").header(
                "Date", 12, "Jour", 11, "Uniques", 10, "Nouveaux", 10, "Sessions", 10, "Pic", 8,
                "Temps de jeu", 13, "Temps AFK", 12, "% AFK", 9, "Durée médiane", 13, "Durée moyenne", 13,
                "Timeouts", 10, "TPS moy.", 10, "TPS min", 10, "MSPT moy.", 11, "MSPT max", 11,
                "RAM moy. (Mo)", 13, "Chunks moy.", 12, "Entités moy.", 12, "Ping moy. (ms)", 13);
        for (DailyRecord r : d.days()) {
            w.row().date(r.day()).text(r.day().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE))
                    .integer(r.uniques()).integer(r.newPlayers()).integer(r.sessions()).integer(r.peakOnline())
                    .duration(r.playMs()).duration(r.afkMs())
                    .percent(r.afkMs() == null || r.playMs() == 0 ? null : (double) r.afkMs() / r.playMs())
                    .duration(r.medianSessionMs()).duration(r.avgSessionMs()).integer(r.timeouts())
                    .decimal(r.tpsAvg(), "0.00").decimal(r.tpsMin(), "0.00").decimal(r.msptAvg(), "0.0").decimal(r.msptMax(), "0.0")
                    .integer(r.ramUsedAvgMb()).integer(r.chunksAvg()).integer(r.entitiesAvg()).decimal(r.pingAvg(), "0");
        }
        w.finish();
    }

    private static void hourly(Workbook wb, ExportData d) {
        SheetWriter w = new SheetWriter(wb, "Horaire").header(
                "Date et heure", 17, "Uniques", 10, "Pic", 8, "Sessions commencées", 12, "Temps de jeu", 13,
                "Connectés moy.", 12, "MSPT moy.", 11, "MSPT max", 11, "TPS min", 10);
        for (ExportData.HourRow h : d.hours()) {
            w.row().dateTime(h.hour()).integer(h.uniques()).integer(h.peak()).integer(h.sessions()).duration(h.playMs())
                    .decimal(h.onlineAvg(), "0.0").decimal(h.msptAvg(), "0.0").decimal(h.msptMax(), "0.0").decimal(h.tpsMin(), "0.00");
        }
        w.finish();
    }

    private static void players(Workbook wb, ExportData d, Map<UUID, String> pseudonyms) {
        boolean anonymous = pseudonyms != null;
        Object[] columns = anonymous
                ? new Object[]{"Joueur", 16}
                : new Object[]{"Pseudo", 18, "UUID", 38};
        Object[] rest = {"Première connexion", 17, "Dernière connexion", 17, "Nouveau sur la période", 12,
                "Sessions", 10, "Jours actifs", 10, "Temps de jeu", 13, "Durée médiane", 13, "Temps AFK", 12, "% AFK", 9,
                "Version", 11, "Client", 16, "Langue", 9};
        Object[] header = new Object[columns.length + rest.length];
        System.arraycopy(columns, 0, header, 0, columns.length);
        System.arraycopy(rest, 0, header, columns.length, rest.length);

        SheetWriter w = new SheetWriter(wb, "Joueurs").header(header);
        for (ExportData.PlayerRow p : d.players()) {
            w.row();
            if (anonymous) w.text(pseudonyms.get(p.uuid()));
            else w.text(p.name()).text(p.uuid().toString());
            w.dateTime(local(p.firstSeen(), d)).dateTime(local(p.lastSeen(), d)).text(p.newInPeriod() ? "Oui" : "Non")
                    .integer(p.sessions()).integer(p.activeDays()).duration(p.playMs()).duration(p.medianMs()).duration(p.afkMs())
                    .percent(p.afkMs() == null || p.playMs() == 0 ? null : (double) p.afkMs() / p.playMs())
                    .text(p.version()).text(p.brand()).text(p.locale());
        }
        w.finish();
    }

    private static void retention(Workbook wb, ExportData d) {
        SheetWriter w = new SheetWriter(wb, "Rétention").header(
                "Semaine du", 12, "Nouveaux", 10,
                "J1 mesurés", 10, "J1 retours", 10, "J1", 9,
                "J7 mesurés", 10, "J7 retours", 10, "J7", 9,
                "J30 mesurés", 11, "J30 retours", 11, "J30", 9,
                "One-shot mesurés", 11, "One-shot", 10, "% one-shot", 10);
        for (Retention.Cohort c : d.cohorts()) {
            w.row().date(c.weekStart()).integer(c.size())
                    .integer(c.d1().measured()).integer(c.d1().hits()).percent(c.d1().value())
                    .integer(c.d7().measured()).integer(c.d7().hits()).percent(c.d7().value())
                    .integer(c.d30().measured()).integer(c.d30().hits()).percent(c.d30().value())
                    .integer(c.oneShot().measured()).integer(c.oneShot().hits()).percent(c.oneShot().value());
        }
        w.finish();
    }

    private static void performance(Workbook wb, ExportData d) {
        SheetWriter w = new SheetWriter(wb, "Performance").header(
                "Date et heure", 17, "Connectés", 10, "TPS 1 min", 10, "TPS 5 min", 10, "TPS 15 min", 10,
                "MSPT moy.", 11, "MSPT max", 11, "RAM utilisée (Mo)", 13, "RAM max (Mo)", 12,
                "Chunks", 10, "Entités", 10, "Ping moy. (ms)", 13);
        for (SnapshotRecord s : d.snapshots()) {
            w.row().dateTime(local(s.ts(), d)).integer(s.online())
                    .decimal(s.tps1m(), "0.00").decimal(s.tps5m(), "0.00").decimal(s.tps15m(), "0.00")
                    .decimal(s.msptAvg(), "0.0").decimal(s.msptMax(), "0.0")
                    .integer(s.ramUsedMb()).integer(s.ramMaxMb()).integer(s.chunks()).integer(s.entities())
                    .decimal(s.pingAvg(), "0");
        }
        w.finish();
    }
}
