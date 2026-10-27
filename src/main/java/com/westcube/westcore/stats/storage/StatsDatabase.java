package com.westcube.westcore.stats.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exécute toutes les opérations de StatsStorage sur un thread unique "WestCore-Stats" :
 * écritures dans l'ordre d'arrivée, aucune I/O sur le thread principal.
 */
public final class StatsDatabase {

    private final StatsStorage storage;
    private final Logger logger;
    private final ExecutorService executor;

    public StatsDatabase(StatsStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WestCore-Stats");
            t.setDaemon(true);
            return t;
        });
    }

    public String serverId() {
        return storage.serverId();
    }

    /** Ouvre la base sur le thread dédié. */
    public CompletableFuture<Void> open() {
        return run(StatsStorage::open);
    }

    /** Requête avec résultat. */
    public <T> CompletableFuture<T> call(Function<StatsStorage, T> query) {
        return CompletableFuture.supplyAsync(() -> query.apply(storage), executor);
    }

    /** Écriture ; une erreur est journalisée (et transmise au futur). */
    public CompletableFuture<Void> run(Consumer<StatsStorage> work) {
        return CompletableFuture.runAsync(() -> {
            try {
                work.accept(storage);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[Stats] Erreur base de données", e);
                throw e;
            }
        }, executor);
    }

    /**
     * Termine les écritures en attente puis ferme la base.
     * Le thread appelant attend au plus timeoutSeconds (arrêt du serveur).
     */
    public void shutdown(long timeoutSeconds) {
        executor.execute(storage::close);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warning("[Stats] Écritures non terminées après " + timeoutSeconds + " s, abandon.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
