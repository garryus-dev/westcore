package com.westcube.westcore.stats;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Text;
import com.westcube.westcore.stats.aggregate.DailyAggregator;
import com.westcube.westcore.stats.collect.SessionTracker;
import com.westcube.westcore.stats.collect.SnapshotCollector;
import com.westcube.westcore.stats.service.StatsService;
import com.westcube.westcore.stats.service.Views;
import com.westcube.westcore.stats.storage.SessionRecord;
import com.westcube.westcore.stats.storage.SqliteStatsStorage;
import com.westcube.westcore.stats.storage.StatsDatabase;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.PermissionDefault;

import com.westcube.westcore.stats.menu.StatsMenu;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

import com.westcube.westcore.stats.export.ExportData;
import com.westcube.westcore.stats.export.ExportPeriod;
import com.westcube.westcore.stats.export.StatsExporter;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Statistiques de fréquentation et de performance (réservées aux admins).
 * collecte -> StatsDatabase (thread dédié) -> StatsService -> menu / commande / export.
 *
 * Un /westcore reload ne coupe pas les sessions en cours : elles restent en mémoire
 * et sont reprises au redémarrage du module. Seul un vrai arrêt les ferme.
 */
public class StatsModule extends Module {

    private final SessionTracker tracker;
    private StatsSettings settings;
    private StatsDatabase db;
    private SnapshotCollector snapshots;
    private DailyAggregator aggregator;
    private StatsService service;
    private File exportsDir;
    /** Faux après le premier démarrage : les sessions encore ouvertes en base sont alors les nôtres. */
    private boolean firstStart = true;
    private volatile boolean exporting;
    private long lastExport;

    public StatsModule(WestCore plugin) {
        super(plugin, "stats");
        this.tracker = new SessionTracker(plugin);
        permission("view", "westcore.stats.view", PermissionDefault.OP, "Consulter les statistiques");
        permission("export", "westcore.stats.export", PermissionDefault.OP, "Exporter les statistiques en .xlsx");
        permission("exempt", "westcore.stats.exempt", PermissionDefault.FALSE, "Exclu de toute collecte de statistiques");
        command("stats", "statistiques de fréquentation", "view", "export");
    }

    @Override
    protected void onEnable() {
        settings = StatsSettings.load(cfg(), plugin.getLogger());
        exportsDir = new File(plugin.getDataFolder(), "exports");
        File file = new File(new File(plugin.getDataFolder(), "data"), "stats.db");
        db = new StatsDatabase(new SqliteStatsStorage(file, settings.serverId(), Bukkit::isPrimaryThread), plugin.getLogger());
        db.open().exceptionally(e -> {
            plugin.getLogger().severe("[Stats] Base inutilisable : " + e.getMessage());
            return null;
        });

        if (firstStart) {
            firstStart = false;
            db.call(s -> s.closeOrphanSessions(SessionRecord.CRASH)).thenAccept(n -> {
                if (n > 0) plugin.getLogger().info("[Stats] " + n + " session(s) interrompue(s) par un arrêt brutal clôturée(s).");
            });
        }

        tracker.attach(db, p -> has(p, "exempt"), settings.countIdleTime());
        snapshots = new SnapshotCollector(db, tracker, settings.snapshotIntervalMinutes());
        aggregator = new DailyAggregator(db, settings, exportsDir, plugin.getLogger());
        service = new StatsService(db, settings.zone(), settings.cacheSeconds(), settings.oneShotMinutes(), this::liveState);
        Bukkit.getPluginManager().registerEvents(tracker, plugin);
        Bukkit.getPluginManager().registerEvents(snapshots, plugin);

        // Jours manqués pendant l'arrêt du serveur
        aggregator.run(System.currentTimeMillis());
        repeat(() -> {
            long now = System.currentTimeMillis();
            tracker.tickAfk(now);
            snapshots.tick(now);
            aggregator.tick(now);
        }, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        // Pendant l'arrêt du plugin, isEnabled() est déjà faux ; pendant un /westcore reload, il est vrai
        if (!plugin.isEnabled()) tracker.closeAll(SessionRecord.SHUTDOWN);
        HandlerList.unregisterAll(tracker);
        if (snapshots != null) HandlerList.unregisterAll(snapshots);
        tracker.detach();
        if (db != null) {
            db.shutdown(10);
            db = null;
        }
        service = null;
    }

    /** État en direct, lu sur le thread principal. */
    private Views.LiveState liveState() {
        long now = System.currentTimeMillis();
        Set<UUID> online = new HashSet<>();
        for (SessionTracker.Live l : tracker.sessions()) online.add(l.uuid());
        return new Views.LiveState(now, online, snapshots.peek(now));
    }

    public StatsSettings settings() {
        return settings;
    }

    public StatsService service() {
        return service;
    }

    public WestCore plugin() {
        return plugin;
    }

    public boolean canExport(Permissible who) {
        return has(who, "export");
    }

    // ------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("export")) {
            if (!has(sender, "export")) {
                sender.sendMessage(noPermission());
                return true;
            }
            export(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "30d");
            return true;
        }
        if (!has(sender, "view")) {
            sender.sendMessage(noPermission());
            return true;
        }
        if (sender instanceof Player p) {
            StatsMenu menu = new StatsMenu(this, p);
            menu.show(StatsMenu.Page.OVERVIEW);
            menu.open(p);
            return true;
        }
        summary(sender);
        return true;
    }

    /**
     * Export .xlsx : collecte sur le thread de la base, écriture du fichier sur un thread de fond.
     * Un seul export à la fois, puis cooldown.
     */
    public void export(CommandSender sender, String arg) {
        ExportPeriod period = ExportPeriod.parse(arg);
        if (period == null) {
            sender.sendMessage(msg("export-usage"));
            return;
        }
        long now = System.currentTimeMillis();
        if (exporting) {
            sender.sendMessage(msg("export-running"));
            return;
        }
        long wait = lastExport + settings.exportCooldownSeconds() * 1000L - now;
        if (wait > 0) {
            sender.sendMessage(msg("export-cooldown", "seconds", String.valueOf((wait + 999) / 1000)));
            return;
        }
        exporting = true;
        lastExport = now;
        sender.sendMessage(msg("export-started", "period", period.label));

        File dir = exportsDir;
        boolean anonymize = settings.exportAnonymize();
        long oneShotMs = settings.oneShotMinutes() * 60_000L;
        java.time.ZoneId zone = settings.zone();
        db.call(s -> ExportData.collect(s, zone, period, now, oneShotMs))
                .thenApplyAsync(data -> {
                    try {
                        return StatsExporter.write(data, dir, anonymize);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((file, error) -> {
                    long ms = System.currentTimeMillis() - now;
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Stats] Export impossible", error);
                    } else {
                        plugin.getLogger().info("[Stats] Export " + period.id + " : " + file.getName() + " (" + ms + " ms).");
                    }
                    if (!plugin.isEnabled()) {
                        exporting = false;
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        exporting = false;
                        if (sender instanceof Player p && !p.isOnline()) return;
                        if (error != null) {
                            sender.sendMessage(msg("export-failed"));
                        } else {
                            sender.sendMessage(msg("export-done", "file", file.getName(),
                                    "size", String.valueOf(Math.max(1, file.length() / 1024)), "seconds",
                                    StatsText.decimal(ms / 1000.0, 1)));
                        }
                    });
                });
    }

    private void summary(CommandSender sender) {
        sender.sendMessage(msg("loading"));
        service.overview().whenComplete((overview, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                sender.sendMessage(msg("error"));
                plugin.getLogger().warning("[Stats] Résumé impossible : " + error.getMessage());
                return;
            }
            for (String line : StatsText.summary(overview, settings)) sender.sendMessage(Text.mm(line));
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        if (!has(sender, "export")) return List.of();
        if (args.length == 1) return "export".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("export") : List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("export")) {
            return Stream.of("7d", "30d", "90d", "all").filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
