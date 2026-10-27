package com.westcube.westcore.stats.menu;

import com.westcube.westcore.core.Text;
import com.westcube.westcore.core.menu.Items;
import com.westcube.westcore.core.menu.Menu;
import com.westcube.westcore.stats.StatsModule;
import com.westcube.westcore.stats.StatsSettings;
import com.westcube.westcore.stats.service.StatsService;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.PlayerSummary;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import com.westcube.westcore.core.menu.Heads;
import java.util.logging.Level;

/**
 * Menu des statistiques (54 slots) : lignes 1 à 5 = contenu de la page, ligne 6 = navigation commune.
 * Aucune requête ici : tout passe par StatsService (asynchrone, en cache) puis revient sur le thread principal.
 */
public final class StatsMenu extends Menu {

    public enum Page { OVERVIEW, FREQUENCY, RETENTION, PLAYERS, PERFORMANCE }

    static final int CONTENT = 45;
    static final int SLOT_CONTEXT_1 = 50;
    static final int SLOT_CONTEXT_2 = 51;
    private static final int SLOT_EXPORT = 52;
    private static final int SLOT_CLOSE = 53;

    /** Colonne d'histogramme : hauteur proportionnelle à value, lore identique sur toute la colonne. */
    record Column(double value, Material filled, String name, List<String> lore) {}

    /** Valeur d'une colonne sans données : toute la colonne prend la couleur "filled" (gris). */
    static final double NO_DATA = -1;

    final StatsModule module;
    final Player viewer;
    Page page = Page.OVERVIEW;
    Views.FrequencyMode frequencyMode = Views.FrequencyMode.DAYS_9;
    Views.PerfMode perfMode = Views.PerfMode.HOURS_24;
    PlayerSummary.Sort sort = PlayerSummary.Sort.PLAYTIME;
    int playerPage;
    private int token;

    public StatsMenu(StatsModule module, Player viewer) {
        super(6, Text.mm("<dark_gray>Statistiques WestCube"));
        this.module = module;
        this.viewer = viewer;
    }

    StatsService service() {
        return module.service();
    }

    StatsSettings settings() {
        return module.settings();
    }

    /** Accès aux slots pour les classes de pages. */
    void put(int slot, ItemStack item, Action action) {
        set(slot, item, action);
    }

    void put(int slot, ItemStack item) {
        set(slot, item, null);
    }

    // -------------------------------------------------------------- rendu

    public void show(Page target) {
        page = target;
        render();
    }

    void render() {
        int current = ++token;
        clear();
        nav();
        switch (page) {
            case OVERVIEW -> load(current, service().overview(), d -> OverviewPage.render(this, d));
            case FREQUENCY -> load(current, service().frequency(frequencyMode), d -> FrequencyPage.render(this, d));
            case RETENTION -> load(current, service().retention(), d -> RetentionPage.render(this, d));
            case PLAYERS -> load(current, service().players(sort, playerPage), d -> PlayersPage.render(this, d));
            case PERFORMANCE -> load(current, service().performance(perfMode), d -> PerformancePage.render(this, d));
        }
    }

    /** Affiche « Chargement… » puis le résultat, s'il concerne encore la page affichée. */
    private <T> void load(int current, CompletableFuture<T> future, Consumer<T> renderer) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            renderer.accept(future.join()); // en cache : pas de clignotement
            return;
        }
        put(22, Items.of(Material.CLOCK).name("<gray>Chargement…").build());
        future.whenComplete((data, error) -> Bukkit.getScheduler().runTask(module.plugin(), () -> {
            if (current != token || !isViewed()) return;
            clear(0, CONTENT);
            if (error != null || data == null) {
                if (error != null) module.plugin().getLogger().log(Level.WARNING, "[Stats] Calcul du menu impossible", error);
                put(22, Items.of(Material.BARRIER).name("<red>Données indisponibles")
                        .lore("<gray>Voir la console du serveur.").build());
                return;
            }
            renderer.accept(data);
        }));
    }

    private void nav() {
        ItemStack filler = Items.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int slot = CONTENT; slot < 54; slot++) put(slot, filler);
        navButton(45, Page.OVERVIEW, Material.NETHER_STAR, "Vue d'ensemble", "Connectés, uniques, nouveaux, serveur");
        navButton(46, Page.FREQUENCY, Material.SPYGLASS, "Fréquentation", "Joueurs par heure, jour, semaine");
        navButton(47, Page.RETENTION, Material.TOTEM_OF_UNDYING, "Rétention", "Retour des nouveaux joueurs");
        navButton(48, Page.PLAYERS, Material.PLAYER_HEAD, "Joueurs", "Liste et fiche de chaque joueur");
        navButton(49, Page.PERFORMANCE, Material.COMPARATOR, "Performance", "TPS, MSPT, mémoire");
        if (module.canExport(viewer)) {
            put(SLOT_EXPORT, Items.of(Material.WRITABLE_BOOK).name("<#ffd027>Exporter (.xlsx)")
                    .lore("<gray>Clic gauche : <white>30 jours", "<gray>Clic droit : <white>7 jours",
                            "<gray>Shift + clic : <white>90 jours", "",
                            "<dark_gray>Fichier dans plugins/WestCore/exports/").build(), (p, click) -> {
                String period = click.isShiftClick() ? "90d" : click.isRightClick() ? "7d" : "30d";
                p.closeInventory();
                module.export(p, period);
            });
        }
        put(SLOT_CLOSE, Items.of(Material.BARRIER).name("<red>Fermer").build(), (p, click) -> p.closeInventory());
    }

    private void navButton(int slot, Page target, Material material, String name, String description) {
        boolean current = page == target;
        put(slot, Items.of(material).name((current ? "<#ffd027>" : "<white>") + name)
                .lore("<gray>" + description, "", current ? "<dark_gray>Page actuelle" : "<yellow>Clic pour afficher")
                .glint(current).build(), current ? null : (p, click) -> show(target));
    }

    // ------------------------------------------------------------- outils

    /**
     * Histogramme en vitres sur les lignes [topRow, topRow + levels[, colonnes centrées.
     * Le détail (lore) n'est porté que par le sommet de la barre (la case du bas si la barre est vide) ;
     * les autres vitres n'affichent que le nom de la colonne.
     */
    void histogram(List<Column> columns, int topRow, int levels, double minScale) {
        double max = minScale;
        for (Column c : columns) max = Math.max(max, c.value());
        int offset = (9 - columns.size()) / 2;
        for (int i = 0; i < columns.size() && i < 9; i++) {
            Column c = columns.get(i);
            int height = c.value() == NO_DATA ? levels
                    : c.value() <= 0 || max <= 0 ? 0 : Math.max(1, (int) Math.round(c.value() / max * levels));
            int detailLevel = c.value() == NO_DATA ? 0 : Math.max(0, height - 1);
            for (int level = 0; level < levels; level++) {
                int row = topRow + levels - 1 - level;
                Items item = Items.of(level < height ? c.filled() : Material.BLACK_STAINED_GLASS_PANE).name(c.name());
                if (level == detailLevel) item.lore(c.lore());
                put(row * 9 + offset + i, item.build());
            }
        }
    }

    static Material pane(StatsSettings.Level level) {
        return switch (level) {
            case GOOD -> Material.LIME_STAINED_GLASS_PANE;
            case WARN -> Material.ORANGE_STAINED_GLASS_PANE;
            case BAD -> Material.RED_STAINED_GLASS_PANE;
        };
    }

    /** Pose une tête ; si le skin n'est pas encore connu, la remplace dès qu'il l'est (page inchangée). */
    void head(int slot, UUID uuid, String name, Function<Items, ItemStack> decorate, Action action) {
        put(slot, decorate.apply(Items.head(uuid, name)), action);
        if (Heads.cached(uuid) != null) return;
        int current = token;
        Heads.resolve(uuid, name).thenAccept(textures -> {
            if (textures == null || !module.plugin().isEnabled()) return;
            Bukkit.getScheduler().runTask(module.plugin(), () -> {
                if (current == token && isViewed()) put(slot, decorate.apply(Items.head(uuid, name)), action);
            });
        });
    }

    void openCard(UUID uuid) {
        new PlayerCardMenu(this, uuid).open(viewer);
    }
}
