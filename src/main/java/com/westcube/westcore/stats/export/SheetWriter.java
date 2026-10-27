package com.westcube.westcore.stats.export;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Onglet au format commun : en-têtes en gras sur fond coloré, première ligne figée, filtre automatique,
 * largeurs fixées. Les dates sont de vraies dates Excel, les durées des fractions de jour ([h]:mm)
 * et les taux des décimaux (format %) : sommes et moyennes fonctionnent dans le tableur.
 */
final class SheetWriter {

    static final String HEADER_FILL = "E87E28";
    private static final double DAY_MS = 86_400_000.0;

    private final Worksheet ws;
    private int columns;
    private int row;
    private int col;

    SheetWriter(Workbook workbook, String name) {
        this.ws = workbook.newWorksheet(name);
    }

    /** Colonnes : alternance nom, largeur. */
    SheetWriter header(Object... nameWidth) {
        columns = nameWidth.length / 2;
        for (int i = 0; i < columns; i++) {
            ws.value(0, i, (String) nameWidth[2 * i]);
            ws.style(0, i).bold().fontColor("FFFFFF").fillColor(HEADER_FILL).wrapText(true).verticalAlignment("center").set();
            ws.width(i, ((Number) nameWidth[2 * i + 1]).doubleValue());
        }
        row = 0;
        return this;
    }

    SheetWriter row() {
        row++;
        col = 0;
        return this;
    }

    SheetWriter text(String value) {
        if (value != null) ws.value(row, col, value);
        col++;
        return this;
    }

    SheetWriter integer(Number value) {
        if (value != null) {
            ws.value(row, col, value);
            ws.style(row, col).format("0").set();
        }
        col++;
        return this;
    }

    SheetWriter decimal(Number value, String format) {
        if (value != null) {
            ws.value(row, col, value);
            ws.style(row, col).format(format).set();
        }
        col++;
        return this;
    }

    SheetWriter date(LocalDate value) {
        if (value != null) {
            ws.value(row, col, value);
            ws.style(row, col).format("dd/mm/yyyy").set();
        }
        col++;
        return this;
    }

    SheetWriter dateTime(LocalDateTime value) {
        if (value != null) {
            ws.value(row, col, value);
            ws.style(row, col).format("dd/mm/yyyy hh:mm").set();
        }
        col++;
        return this;
    }

    /** Durée en fraction de jour, affichée en heures:minutes (au-delà de 24 h sans retour à zéro). */
    SheetWriter duration(Long ms) {
        if (ms != null) {
            ws.value(row, col, ms / DAY_MS);
            ws.style(row, col).format("[h]:mm").set();
        }
        col++;
        return this;
    }

    /** Taux en décimal (0.42), affiché 42,0 %. */
    SheetWriter percent(Double ratio) {
        if (ratio != null) {
            ws.value(row, col, ratio);
            ws.style(row, col).format("0.0%").set();
        }
        col++;
        return this;
    }

    void finish() {
        ws.freezePane(0, 1);
        ws.setAutoFilter(0, 0, Math.max(row, 1), columns - 1);
    }
}
