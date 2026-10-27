package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.PlayerSummary;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

import static com.westcube.westcore.stats.StatsText.*;

/** 45 têtes par page ; slot 50 = tri, slot 51 = pagination ; clic sur une tête = fiche joueur. */
final class PlayersPage {

    private PlayersPage() {}

    static String sortLabel(PlayerSummary.Sort sort) {
        return switch (sort) {
            case PLAYTIME -> "Temps de jeu (30 j)";
            case LAST_SEEN -> "Dernière connexion";
            case SESSIONS -> "Sessions (30 j)";
            case NEWEST -> "Nouveaux joueurs";
        };
    }

    static void render(StatsMenu menu, Views.PlayerPage page) {
        long now = System.currentTimeMillis();
        menu.playerPage = page.page();
        for (int i = 0; i < page.players().size() && i < StatsMenu.CONTENT; i++) {
            PlayerSummary p = page.players().get(i);
            boolean online = page.online().contains(p.uuid());
            int rank = page.page() * 45 + i + 1;
            menu.head(i, p.uuid(), p.name(), head -> head
                    .name((online ? "<green>" : "<white>") + p.name() + " <dark_gray>#" + rank)
                    .lore("<gray>Temps de jeu (30 j) : <white>" + duration(p.playMs()),
                            "<gray>Sessions (30 j) : <white>" + p.sessions(),
                            "<gray>Dernière connexion : " + (online ? "<green>en ligne" : "<white>" + ago(p.lastSeen(), now)),
                            "<gray>Première connexion : <white>" + date(p.firstSeen(), menu.service().zone()),
                            "",
                            "<yellow>Clic : fiche du joueur").build(), (viewer, click) -> menu.openCard(p.uuid()));
        }
        if (page.players().isEmpty()) {
            menu.put(22, Items.of(Material.STRUCTURE_VOID).name("<gray>Aucun joueur enregistré").build());
        }

        List<String> sortLore = new ArrayList<>();
        for (PlayerSummary.Sort s : PlayerSummary.Sort.values()) {
            sortLore.add((s == page.sort() ? "<#ffd027>▶ " : "<gray>  ") + sortLabel(s));
        }
        sortLore.add("");
        sortLore.add("<yellow>Clic : tri suivant");
        menu.put(StatsMenu.SLOT_CONTEXT_1, Items.of(Material.HOPPER).name("<#ffd027>Tri : <white>" + sortLabel(page.sort()))
                .lore(sortLore).build(), (viewer, click) -> {
            PlayerSummary.Sort[] all = PlayerSummary.Sort.values();
            menu.sort = all[(page.sort().ordinal() + 1) % all.length];
            menu.playerPage = 0;
            menu.render();
        });

        menu.put(StatsMenu.SLOT_CONTEXT_2, Items.of(Material.ARROW).amount(page.page() + 1)
                .name("<#ffd027>Page <white>" + (page.page() + 1) + " / " + page.pages())
                .lore("<gray>" + page.total() + " joueur(s) enregistré(s)", "",
                        page.page() + 1 < page.pages() ? "<yellow>Clic gauche : page suivante" : "<dark_gray>Dernière page",
                        page.page() > 0 ? "<yellow>Clic droit : page précédente" : "<dark_gray>Première page").build(), (viewer, click) -> {
            int target = page.page() + (click.isRightClick() ? -1 : 1);
            if (target < 0 || target >= page.pages()) return;
            menu.playerPage = target;
            menu.render();
        });
    }
}
