package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.DailyRecord;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import org.bukkit.Material;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.westcube.westcore.stats.StatsText.*;

/** Ligne 1 : chiffres du jour comparés à hier. Lignes 2 à 5 : joueurs uniques sur 9 jours. */
final class OverviewPage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private OverviewPage() {}

    static void render(StatsMenu menu, Views.Overview o) {
        DailyRecord t = o.today();
        DailyRecord y = o.yesterday();
        boolean hasYesterday = y != null && (y.uniques() > 0 || y.tpsAvg() != null);
        SnapshotRecord c = o.current();
        StatsSettings s = menu.settings();

        menu.put(0, Items.of(Material.EMERALD).amount(o.online())
                .name("<#ffd027>Connectés : <white>" + o.online())
                .lore("<gray>Joueurs en ligne en ce moment", "<dark_gray>(hors joueurs exemptés)").build());
        menu.put(1, Items.of(Material.FIREWORK_ROCKET).amount(t.peakOnline())
                .name("<#ffd027>Pic du jour : <white>" + t.peakOnline())
                .lore("<gray>Maximum de joueurs simultanés", delta(t.peakOnline(), hasYesterday ? (double) y.peakOnline() : null, true, false)).build());
        menu.put(2, Items.of(Material.PLAYER_HEAD).amount(t.uniques())
                .name("<#ffd027>Joueurs uniques : <white>" + t.uniques())
                .lore("<gray>Joueurs différents connectés aujourd'hui", delta(t.uniques(), hasYesterday ? (double) y.uniques() : null, true, false)).build());
        menu.put(3, Items.of(Material.OAK_SAPLING).amount(t.newPlayers())
                .name("<#ffd027>Nouveaux : <white>" + t.newPlayers())
                .lore("<gray>Première connexion aujourd'hui", delta(t.newPlayers(), hasYesterday ? (double) y.newPlayers() : null, true, false)).build());
        menu.put(4, Items.of(Material.CLOCK)
                .name("<#ffd027>Durée médiane : <white>" + duration(t.medianSessionMs()))
                .lore("<gray>Session typique commencée aujourd'hui",
                        "<gray>Moyenne : <white>" + duration(t.avgSessionMs()) + " <dark_gray>(" + t.sessions() + " sessions)",
                        delta(t.medianSessionMs(), hasYesterday && y.sessions() > 0 ? (double) y.medianSessionMs() : null, true, true)).build());
        List<String> playLore = new ArrayList<>(List.of("<gray>Total joué aujourd'hui, tous joueurs",
                delta(t.playMs(), hasYesterday ? (double) y.playMs() : null, true, true)));
        if (t.afkMs() != null && t.playMs() > 0) {
            playLore.add("<gray>Dont AFK : <white>" + duration(t.afkMs()) + " <dark_gray>(" + percent((double) t.afkMs() / t.playMs()) + ")");
        }
        menu.put(5, Items.of(Material.HOPPER).name("<#ffd027>Temps de jeu : <white>" + duration(t.playMs())).lore(playLore).build());

        String tps = level(s.tps().level(c.tps1m()));
        menu.put(6, Items.of(Material.REPEATER)
                .name("<#ffd027>TPS : <" + tps + ">" + decimal(c.tps1m(), 1))
                .lore("<gray>1 min : <white>" + decimal(c.tps1m(), 2), "<gray>5 min : <white>" + decimal(c.tps5m(), 2),
                        "<gray>15 min : <white>" + decimal(c.tps15m(), 2),
                        hasYesterday && y.tpsAvg() != null ? "<dark_gray>Moyenne d'hier : " + decimal(y.tpsAvg(), 2) : "<dark_gray>(hier : —)").build());
        String mspt = level(s.mspt().level(c.msptAvg()));
        menu.put(7, Items.of(Material.COMPARATOR)
                .name("<#ffd027>MSPT : <" + mspt + ">" + decimal(c.msptAvg(), 1) + " ms")
                .lore("<gray>Temps moyen d'un tick (50 ms = limite)",
                        "<gray>Pire tick récent : <white>" + decimal(c.msptMax(), 1) + " ms",
                        hasYesterday && y.msptAvg() != null
                                ? delta(c.msptAvg(), y.msptAvg(), false, false) + " <dark_gray>ms"
                                : "<dark_gray>(hier : —)").build());
        menu.put(8, Items.of(Material.ENDER_CHEST)
                .name("<#ffd027>RAM : <white>" + integer(c.ramUsedMb()) + " <gray>/ " + integer(c.ramMaxMb()) + " Mo")
                .lore("<gray>Utilisée : <white>" + percent((double) c.ramUsedMb() / Math.max(1, c.ramMaxMb())),
                        hasYesterday && y.ramUsedAvgMb() != null
                                ? delta(c.ramUsedMb(), (double) y.ramUsedAvgMb(), false, false) + " <dark_gray>Mo (moyenne d'hier)"
                                : "<dark_gray>(hier : —)").build());

        List<StatsMenu.Column> columns = new ArrayList<>();
        LocalDate today = t.day();
        for (DailyRecord d : o.lastDays()) {
            boolean isToday = d.day().equals(today);
            String dayName = d.day().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRANCE);
            columns.add(new StatsMenu.Column(d.uniques(),
                    isToday ? Material.LIME_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    "<#ffd027>" + dayName + " " + d.day().format(DAY) + (isToday ? " <gray>(en cours)" : ""),
                    List.of("<gray>Joueurs uniques : <white>" + d.uniques(),
                            "<gray>Nouveaux : <white>" + d.newPlayers(),
                            "<gray>Pic simultané : <white>" + d.peakOnline(),
                            "<gray>Sessions : <white>" + d.sessions(),
                            "<gray>Temps de jeu : <white>" + duration(d.playMs()),
                            "<gray>Durée médiane : <white>" + duration(d.medianSessionMs()))));
        }
        menu.histogram(columns, 1, 4, 1);
    }
}
