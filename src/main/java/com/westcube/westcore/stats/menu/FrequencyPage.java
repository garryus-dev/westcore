package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.stats.service.Views;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static com.westcube.westcore.stats.StatsText.*;

/** Histogramme sur 5 niveaux ; slot 50 = mode (24 h, jours de la semaine, 9 jours, 9 semaines). */
final class FrequencyPage {

    private FrequencyPage() {}

    static void render(StatsMenu menu, Views.Frequency f) {
        boolean average = f.mode() == Views.FrequencyMode.WEEKDAYS_30;
        List<StatsMenu.Column> columns = new ArrayList<>();
        double best = 0;
        String bestLabel = "—";
        int newPlayers = 0, sessions = 0;
        long play = 0;
        for (Views.Bucket b : f.buckets()) {
            List<String> lore = new ArrayList<>();
            if (average) {
                lore.add("<gray>Joueurs uniques (moyenne) : <white>" + decimal(b.value(), 1));
                lore.add("<dark_gray>Sur " + b.days() + " jour(s) de données");
                lore.add("<gray>Pic simultané (max) : <white>" + b.peak());
            } else {
                lore.add("<gray>Joueurs uniques : <white>" + b.uniques());
                lore.add("<gray>Pic simultané : <white>" + b.peak());
            }
            lore.add("<gray>Nouveaux : <white>" + b.newPlayers());
            lore.add("<gray>Sessions commencées : <white>" + b.sessions());
            lore.add("<gray>Temps de jeu : <white>" + duration(b.playMs()));
            columns.add(new StatsMenu.Column(b.value(), Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<#ffd027>" + b.label(), lore));
            if (b.value() > best) {
                best = b.value();
                bestLabel = b.label();
            }
            newPlayers += b.newPlayers();
            sessions += b.sessions();
            play += b.playMs();
        }
        menu.histogram(columns, 0, 5, 1);

        List<String> modeLore = new ArrayList<>();
        for (Views.FrequencyMode m : Views.FrequencyMode.values()) {
            modeLore.add((m == f.mode() ? "<#ffd027>▶ " : "<gray>  ") + m.label);
        }
        modeLore.add("");
        modeLore.add("<yellow>Clic : mode suivant");
        menu.put(StatsMenu.SLOT_CONTEXT_1, Items.of(Material.SPYGLASS).name("<#ffd027>Mode : <white>" + f.mode().label)
                .lore(modeLore).build(), (p, click) -> {
            menu.frequencyMode = f.mode().next();
            menu.render();
        });

        menu.put(StatsMenu.SLOT_CONTEXT_2, Items.of(Material.PAPER).name("<#ffd027>Résumé de la période")
                .lore("<gray>Meilleure tranche : <white>" + bestLabel + " <dark_gray>(" + decimal(best, average ? 1 : 0) + ")",
                        "<gray>Nouveaux joueurs : <white>" + newPlayers,
                        "<gray>Sessions : <white>" + sessions,
                        "<gray>Temps de jeu cumulé : <white>" + duration(play),
                        "",
                        "<dark_gray>Hauteur des barres : joueurs uniques"
                                + (average ? " (moyenne par jour)" : "")).build());
    }
}
