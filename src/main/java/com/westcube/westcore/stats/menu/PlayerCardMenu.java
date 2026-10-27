package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.Text;
import com.westcube.westcore.core.menu.Heads;
import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.core.menu.Menu;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.SessionRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static com.westcube.westcore.stats.StatsText.*;

/** Fiche d'un joueur (27 slots), ouverte depuis la page Joueurs ; bouton retour vers la liste. */
final class PlayerCardMenu extends Menu {

    private final StatsMenu parent;
    private final UUID uuid;

    PlayerCardMenu(StatsMenu parent, UUID uuid) {
        super(3, Text.mm("<dark_gray>Fiche joueur"));
        this.parent = parent;
        this.uuid = uuid;
        ItemStack filler = Items.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 18; i < 27; i++) set(i, filler);
        set(18, Items.of(Material.ARROW).name("<#ffd027>Retour à la liste").build(), (p, click) -> back(p));
        set(26, Items.of(Material.BARRIER).name("<red>Fermer").build(), (p, click) -> p.closeInventory());
        set(13, Items.of(Material.CLOCK).name("<gray>Chargement…").build());
        load();
    }

    private void back(Player p) {
        parent.open(p);
        parent.render();
    }

    private void load() {
        parent.service().playerCard(uuid).whenComplete((card, error) -> Bukkit.getScheduler().runTask(parent.module.plugin(), () -> {
            // runTask : toujours au tick suivant, donc après l'ouverture de l'inventaire
            if (!isViewed()) return;
            if (error != null || card == null) {
                if (error != null) parent.module.plugin().getLogger().log(Level.WARNING, "[Stats] Fiche joueur impossible", error);
                set(13, Items.of(Material.BARRIER).name("<red>Joueur introuvable").build());
                return;
            }
            render(card, parent.service().zone());
        }));
    }

    private void render(Views.PlayerCard c, ZoneId zone) {
        long now = System.currentTimeMillis();
        String name = c.player().name();
        set(13, null);
        Runnable head = () -> set(4, Items.head(uuid, name).name((c.online() ? "<green>" : "<white>") + name)
                .lore(c.online() ? "<green>En ligne" : "<gray>Vu " + ago(c.player().lastSeen(), now),
                        "<dark_gray>" + uuid).build());
        head.run();
        if (Heads.cached(uuid) == null) {
            Heads.resolve(uuid, name).thenAccept(textures -> {
                if (textures == null || !parent.module.plugin().isEnabled()) return;
                Bukkit.getScheduler().runTask(parent.module.plugin(), () -> {
                    if (isViewed()) head.run();
                });
            });
        }

        set(9, Items.of(Material.CLOCK).name("<#ffd027>Temps de jeu total : <white>" + duration(c.playMsTotal()))
                .lore("<gray>Sur " + c.sessions() + " session(s) enregistrée(s)").build());
        set(10, Items.of(Material.SUNFLOWER).name("<#ffd027>30 derniers jours : <white>" + duration(c.playMs30d()))
                .lore("<gray>" + c.sessions30d() + " session(s)").build());
        set(11, Items.of(Material.REPEATER).amount(c.sessions()).name("<#ffd027>Sessions : <white>" + c.sessions()).build());
        set(12, Items.of(Material.COMPASS).name("<#ffd027>Durée médiane : <white>" + duration(c.medianSessionMs())).build());
        set(14, Items.of(Material.RED_BED).name("<#ffd027>Temps AFK : <white>" + (c.afkMsTotal() == null ? "non mesuré" : duration(c.afkMsTotal())))
                .lore(c.afkMsTotal() == null || c.playMsTotal() == 0 ? "<dark_gray>—"
                        : "<gray>" + percent((double) c.afkMsTotal() / c.playMsTotal()) + " du temps de jeu").build());
        set(15, Items.of(Material.OAK_SAPLING).name("<#ffd027>Première connexion")
                .lore("<white>" + dateTime(c.player().firstSeen(), zone)).build());
        set(16, Items.of(Material.ENDER_EYE).name("<#ffd027>Dernière connexion")
                .lore(c.online() ? "<green>En ligne" : "<white>" + dateTime(c.player().lastSeen(), zone)).build());

        if (!c.recent().isEmpty()) {
            SessionRecord last = c.recent().getFirst();
            set(20, Items.of(Material.NAME_TAG).name("<#ffd027>Client (dernière session)")
                    .lore("<gray>Version : <white>" + orDash(last.clientVersion())
                                    + (last.protocol() == null ? "" : " <dark_gray>(protocole " + last.protocol() + ")"),
                            "<gray>Client : <white>" + orDash(last.clientBrand()),
                            "<gray>Langue : <white>" + orDash(last.locale()),
                            "<gray>Adresse utilisée : <white>" + orDash(last.hostname())).build());

            List<String> lines = new ArrayList<>();
            for (SessionRecord s : c.recent()) {
                lines.add("<white>" + dateTime(s.startMs(), zone) + " <gray>· " + duration(s.durationMs(now))
                        + (s.isOpen() ? " <green>en cours" : " <dark_gray>" + quitReason(s.quitReason())));
            }
            set(22, Items.of(Material.WRITABLE_BOOK).name("<#ffd027>Dernières sessions").lore(lines).build());
        }
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : Text.escape(s);
    }
}
