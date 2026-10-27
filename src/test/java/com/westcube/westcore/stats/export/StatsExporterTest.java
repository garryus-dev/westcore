package com.westcube.westcore.stats.export;

import com.westcube.westcore.stats.DemoData;
import com.westcube.westcore.stats.storage.SqliteStatsStorage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Export sur 90 jours de données de démonstration, relu avec Apache POI. */
class StatsExporterTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @TempDir
    static File dir;
    static SqliteStatsStorage s;
    static long now;

    @BeforeAll
    static void seed() {
        s = new SqliteStatsStorage(new File(dir, "stats.db"), "westcube", () -> false);
        s.open();
        now = System.currentTimeMillis();
        DemoData.generate(s, PARIS, 90, now, 7);
    }

    @AfterAll
    static void close() {
        s.close();
    }

    static int columnOf(Sheet sheet, String header) {
        for (Cell c : sheet.getRow(0)) if (c.getStringCellValue().equals(header)) return c.getColumnIndex();
        fail("Colonne absente : " + header);
        return -1;
    }

    static double sum(Sheet sheet, int column) {
        double total = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Cell c = sheet.getRow(r).getCell(column);
            if (c != null && c.getCellType() == CellType.NUMERIC) total += c.getNumericCellValue();
        }
        return total;
    }

    @Test
    void workbookStructureAndFormats() throws Exception {
        long t0 = System.nanoTime();
        ExportData data = ExportData.collect(s, PARIS, ExportPeriod.D30, now, 10 * 60_000L);
        File file = StatsExporter.write(data, new File(dir, "exports"), false);
        System.out.printf("Export 30 j : %d Ko en %d ms%n", file.length() / 1024, (System.nanoTime() - t0) / 1_000_000);

        assertTrue(file.getName().matches("stats_\\d{4}-\\d{2}-\\d{2}_30d\\.xlsx"));
        assertFalse(new File(file.getPath() + ".tmp").exists());

        try (XSSFWorkbook wb = new XSSFWorkbook(file)) {
            assertEquals(List.of("Résumé", "Journalier", "Horaire", "Joueurs", "Rétention", "Performance"),
                    List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2), wb.getSheetName(3),
                            wb.getSheetName(4), wb.getSheetName(5)));

            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                // Première ligne figée, filtre automatique, en-tête en gras sur fond coloré
                assertNotNull(sheet.getPaneInformation(), sheet.getSheetName());
                assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition(), sheet.getSheetName());
                assertTrue(sheet.getCTWorksheet().isSetAutoFilter(), sheet.getSheetName());
                Cell header = sheet.getRow(0).getCell(0);
                assertTrue(wb.getFontAt(header.getCellStyle().getFontIndex()).getBold());
                assertNotNull(header.getCellStyle().getFillForegroundColorColor());
            }

            Sheet daily = wb.getSheet("Journalier");
            assertEquals(30, daily.getLastRowNum()); // ligne 0 = en-tête, lignes 1 à 30 = jours
            Cell date = daily.getRow(1).getCell(0);
            assertEquals(CellType.NUMERIC, date.getCellType());
            assertTrue(DateUtil.isCellDateFormatted(date));
            int playCol = columnOf(daily, "Temps de jeu");
            Cell play = daily.getRow(5).getCell(playCol);
            assertEquals(CellType.NUMERIC, play.getCellType());
            assertEquals("[h]:mm", play.getCellStyle().getDataFormatString());
            assertEquals("0.0%", daily.getRow(5).getCell(columnOf(daily, "% AFK")).getCellStyle().getDataFormatString());

            // Les durées se somment : total journalier = total du résumé = total des joueurs
            double dailyTotal = sum(daily, playCol);
            Sheet summary = wb.getSheet("Résumé");
            double summaryTotal = 0;
            for (Row r : summary) {
                if (r.getRowNum() > 0 && r.getCell(0).getStringCellValue().equals("Temps de jeu cumulé")) {
                    summaryTotal = r.getCell(1).getNumericCellValue();
                }
            }
            Sheet players = wb.getSheet("Joueurs");
            double playersTotal = sum(players, columnOf(players, "Temps de jeu"));
            assertEquals(summaryTotal, dailyTotal, 1e-6);
            assertEquals(summaryTotal, playersTotal, 1e-6);
            assertTrue(summaryTotal > 1, "plus de 24 h de jeu cumulé");

            Sheet hourly = wb.getSheet("Horaire");
            assertEquals(dailyTotal, sum(hourly, columnOf(hourly, "Temps de jeu")), 1e-6);
            assertTrue(hourly.getLastRowNum() >= 29 * 24);
            assertEquals("UUID", players.getRow(0).getCell(1).getStringCellValue());

            Sheet perf = wb.getSheet("Performance");
            assertTrue(perf.getLastRowNum() > 8000);
            assertTrue(DateUtil.isCellDateFormatted(perf.getRow(1).getCell(0)));
        }
    }

    @Test
    void anonymizedAndAllPeriods() throws Exception {
        ExportData data = ExportData.collect(s, PARIS, ExportPeriod.ALL, now, 10 * 60_000L);
        File file = StatsExporter.write(data, new File(dir, "anon"), true);
        try (XSSFWorkbook wb = new XSSFWorkbook(file)) {
            Sheet players = wb.getSheet("Joueurs");
            assertEquals("Joueur", players.getRow(0).getCell(0).getStringCellValue());
            assertNotEquals("UUID", players.getRow(0).getCell(1).getStringCellValue());
            for (int r = 1; r <= players.getLastRowNum(); r++) {
                assertTrue(players.getRow(r).getCell(0).getStringCellValue().matches("Joueur #\\d{4}"));
            }
            assertEquals(90, wb.getSheet("Journalier").getLastRowNum());
            assertTrue(wb.getSheet("Rétention").getLastRowNum() >= 13);
        }
        for (ExportPeriod p : ExportPeriod.values()) {
            assertNotNull(StatsExporter.write(ExportData.collect(s, PARIS, p, now, 600_000L), new File(dir, "all"), false));
        }
    }
}
