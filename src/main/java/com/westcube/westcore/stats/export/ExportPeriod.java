package com.westcube.westcore.stats.export;

import java.util.Locale;

/** Période d'export : 7, 30, 90 derniers jours (aujourd'hui inclus) ou tout l'historique. */
public enum ExportPeriod {
    D7("7d", 7, "7 derniers jours"),
    D30("30d", 30, "30 derniers jours"),
    D90("90d", 90, "90 derniers jours"),
    ALL("all", 0, "tout l'historique");

    public final String id;
    public final int days;
    public final String label;

    ExportPeriod(String id, int days, String label) {
        this.id = id;
        this.days = days;
        this.label = label;
    }

    /** null si l'argument n'est pas une période connue. */
    public static ExportPeriod parse(String arg) {
        String a = arg.toLowerCase(Locale.ROOT);
        for (ExportPeriod p : values()) {
            if (p.id.equals(a)) return p;
        }
        return null;
    }
}
