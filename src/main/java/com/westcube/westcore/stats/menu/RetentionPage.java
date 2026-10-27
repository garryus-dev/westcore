package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.service.Retention;
import com.westcube.westcore.stats.service.Views;
import org.bukkit.Material;

import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.westcube.westcore.stats.StatsText.*;

/**
 * Une colonne par cohorte hebdomadaire (de la plus ancienne à la plus récente) :
 * ligne 1 = cohorte, lignes 2 à 4 = J1 / J7 / J30 colorés selon les seuils (gris si non mesurable),
 * ligne 5 = taux de one-shot.
 */
final class RetentionPage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final int[] COLUMNS = {0, 2, 4, 6, 8};

    private RetentionPage() {}

    static void render(StatsMenu menu, Views.RetentionView r) {
        StatsSettings s = menu.settings();
        for (int i = 0; i < r.cohorts().size() && i < COLUMNS.length; i++) {
            Retention.Cohort c = r.cohorts().get(i);
            int col = COLUMNS[i];
            String week = c.weekStart().format(DAY) + " – " + c.weekStart().plusDays(6).format(DAY);
            menu.put(col, Items.of(c.size() > 0 ? Material.BOOK : Material.GRAY_DYE).amount(c.size())
                    .name("<#ffd027>Semaine du " + week)
                    .lore("<gray>Nouveaux joueurs : <white>" + c.size(),
                            c.size() == 0 ? "<dark_gray>Aucun nouveau joueur cette semaine" : "<dark_gray>Première connexion cette semaine").build());
            menu.put(9 + col, rate("J1", "reconnecté le lendemain", c.d1(), s.retentionD1()));
            menu.put(18 + col, rate("J7", "revenu dans les 7 jours", c.d7(), s.retentionD7()));
            menu.put(27 + col, rate("J30", "revenu dans les 30 jours", c.d30(), s.retentionD30()));
            menu.put(36 + col, oneShot(c.oneShot(), s));
        }

        Retention.Rate os = r.oneShot();
        menu.put(StatsMenu.SLOT_CONTEXT_1, Items.of(Material.EGG)
                .name("<#ffd027>One-shot : <white>" + percent(os.value()))
                .lore("<gray>Nouveaux joueurs partis après une première",
                        "<gray>session de moins de <white>" + s.oneShotMinutes() + " min</white>, sans revenir",
                        "<gray>dans les 7 jours (5 dernières cohortes).",
                        "",
                        "<gray>Mesurés : <white>" + os.hits() + " / " + os.measured()).build());

        menu.put(StatsMenu.SLOT_CONTEXT_2, Items.of(Material.PAPER).name("<#ffd027>Lecture du tableau")
                .lore("<gray>Une colonne = les joueurs arrivés",
                        "<gray>la même semaine (lundi → dimanche).",
                        "",
                        "<green>■</green> <gray>J1 ≥ " + percent(s.retentionD1().good()) + ", J7 ≥ " + percent(s.retentionD7().good())
                                + ", J30 ≥ " + percent(s.retentionD30().good()),
                        "<gold>■</gold> <gray>J1 ≥ " + percent(s.retentionD1().warn()) + ", J7 ≥ " + percent(s.retentionD7().warn())
                                + ", J30 ≥ " + percent(s.retentionD30().warn()),
                        "<red>■</red> <gray>en dessous",
                        "<dark_gray>■ <gray>pas encore mesurable",
                        "",
                        "<gray>Un joueur n'est compté qu'une fois",
                        "<gray>sa fenêtre entièrement écoulée.",
                        r.dataSince() == null ? "<dark_gray>Aucune donnée collectée."
                                : "<dark_gray>Données depuis le " + r.dataSince().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).build());
    }

    private static org.bukkit.inventory.ItemStack rate(String label, String meaning, Retention.Rate rate, StatsSettings.Threshold threshold) {
        Double value = rate.value();
        Material material = value == null ? Material.GRAY_STAINED_GLASS_PANE : StatsMenu.pane(threshold.level(value));
        String color = value == null ? "gray" : level(threshold.level(value));
        return Items.of(material)
                .name("<#ffd027>" + label + " : <" + color + ">" + (value == null ? "non mesurable" : percent(value)))
                .lore("<gray>Part des nouveaux joueurs " + meaning,
                        value == null
                                ? "<dark_gray>Fenêtre pas encore écoulée"
                                : "<gray>" + rate.hits() + " / " + rate.measured() + " joueur(s) mesuré(s)").build();
    }

    private static org.bukkit.inventory.ItemStack oneShot(Retention.Rate rate, StatsSettings s) {
        Double value = rate.value();
        return Items.of(value == null ? Material.GRAY_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE)
                .name("<#ffd027>One-shot : <white>" + (value == null ? "non mesurable" : percent(value)))
                .lore("<gray>Première session < " + s.oneShotMinutes() + " min, sans retour sous 7 jours",
                        value == null ? "<dark_gray>Moins de 7 jours de recul"
                                : "<gray>" + rate.hits() + " / " + rate.measured() + " joueur(s) mesuré(s)").build();
    }
}
