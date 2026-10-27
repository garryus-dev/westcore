package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.SnapshotRecord;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static com.westcube.westcore.stats.StatsText.*;

/** Ligne 1 : état en direct. Lignes 2 à 5 : pire MSPT par tranche, coloré par seuils. Slot 50 : 24 h / 7 jours. */
final class PerformancePage {

    private PerformancePage() {}

    static void render(StatsMenu menu, Views.Performance p) {
        StatsSettings s = menu.settings();
        SnapshotRecord c = p.current();
        String tps = level(s.tps().level(c.tps1m()));
        String mspt = level(s.mspt().level(c.msptAvg()));

        menu.put(0, Items.of(Material.REPEATER).name("<#ffd027>TPS : <" + tps + ">" + decimal(c.tps1m(), 2))
                .lore("<gray>1 min : <white>" + decimal(c.tps1m(), 2),
                        "<gray>5 min : <white>" + decimal(c.tps5m(), 2),
                        "<gray>15 min : <white>" + decimal(c.tps15m(), 2)).build());
        menu.put(1, Items.of(Material.COMPARATOR).name("<#ffd027>MSPT : <" + mspt + ">" + decimal(c.msptAvg(), 1) + " ms")
                .lore("<gray>Temps moyen d'un tick", "<gray>Pire tick depuis le dernier relevé : <white>" + decimal(c.msptMax(), 1) + " ms",
                        "<dark_gray>50 ms = limite des 20 TPS").build());
        menu.put(2, Items.of(Material.ENDER_CHEST).name("<#ffd027>RAM : <white>" + integer(c.ramUsedMb()) + " Mo")
                .lore("<gray>Maximum : <white>" + integer(c.ramMaxMb()) + " Mo",
                        "<gray>Utilisée : <white>" + percent((double) c.ramUsedMb() / Math.max(1, c.ramMaxMb()))).build());
        menu.put(3, Items.of(Material.GRASS_BLOCK).name("<#ffd027>Chunks chargés : <white>" + integer(c.chunks())).build());
        menu.put(4, Items.of(Material.ZOMBIE_HEAD).name("<#ffd027>Entités : <white>" + integer(c.entities())).build());
        menu.put(5, Items.of(Material.ECHO_SHARD).name("<#ffd027>Ping moyen : <white>"
                + (c.pingAvg() == null ? "—" : decimal(c.pingAvg(), 0) + " ms")).build());
        menu.put(6, Items.of(Material.EMERALD).amount(c.online()).name("<#ffd027>Connectés : <white>" + c.online()).build());
        menu.put(7, Items.of(Material.COBWEB).amount(p.timeouts24h())
                .name("<#ffd027>Timeouts (24 h) : <white>" + p.timeouts24h())
                .lore("<gray>Déconnexions par perte de connexion").build());

        List<StatsMenu.Column> columns = new ArrayList<>();
        for (Views.PerfBucket b : p.buckets()) {
            if (b.samples() == 0) {
                columns.add(new StatsMenu.Column(StatsMenu.NO_DATA, Material.GRAY_STAINED_GLASS_PANE, "<#ffd027>" + b.label(),
                        List.of("<dark_gray>Aucun relevé")));
                continue;
            }
            Material color = StatsMenu.pane(s.mspt().level(b.msptMax()));
            columns.add(new StatsMenu.Column(b.msptMax(), color, "<#ffd027>" + b.label(), List.of(
                    "<gray>Pire tick : <white>" + decimal(b.msptMax(), 1) + " ms",
                    "<gray>MSPT moyen : <white>" + decimal(b.msptAvg(), 1) + " ms",
                    "<gray>TPS le plus bas : <white>" + decimal(b.tpsMin(), 2),
                    "<gray>Connectés : <white>" + decimal(b.onlineAvg(), 1) + " <dark_gray>en moyenne, <white>" + b.onlineMax() + " <dark_gray>max",
                    "<dark_gray>" + b.samples() + " relevé(s)")));
        }
        // Échelle : au moins le seuil orange, pour qu'un serveur sain garde des barres basses
        menu.histogram(columns, 1, 4, s.mspt().warn());

        menu.put(StatsMenu.SLOT_CONTEXT_1, Items.of(Material.CLOCK).name("<#ffd027>Période : <white>" + p.mode().label)
                .lore((p.mode() == Views.PerfMode.HOURS_24 ? "<#ffd027>▶ " : "<gray>  ") + Views.PerfMode.HOURS_24.label,
                        (p.mode() == Views.PerfMode.DAYS_7 ? "<#ffd027>▶ " : "<gray>  ") + Views.PerfMode.DAYS_7.label,
                        "", "<yellow>Clic : changer").build(), (viewer, click) -> {
            menu.perfMode = p.mode().next();
            menu.render();
        });
        menu.put(StatsMenu.SLOT_CONTEXT_2, Items.of(Material.PAPER).name("<#ffd027>Lecture")
                .lore("<gray>Hauteur des barres : pire tick (MSPT max)",
                        "<green>■</green> <gray>≤ " + decimal(s.mspt().good(), 0) + " ms   <gold>■</gold> <gray>≤ "
                                + decimal(s.mspt().warn(), 0) + " ms   <red>■</red> <gray>au-delà",
                        "<dark_gray>■ <gray>aucun relevé dans la tranche").build());
    }
}
