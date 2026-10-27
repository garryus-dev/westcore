package com.westcube.westcore.stats.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StatsDatabaseTest {

    @TempDir
    File dir;

    @Test
    void runsOnDedicatedThreadAndFlushesOnShutdown() throws Exception {
        Thread caller = Thread.currentThread();
        // Le "thread principal" du test est celui qui soumet : le stockage doit ne jamais s'y exécuter
        SqliteStatsStorage storage = new SqliteStatsStorage(new File(dir, "stats.db"), "test",
                () -> Thread.currentThread() == caller);
        StatsDatabase db = new StatsDatabase(storage, Logger.getLogger("test"));
        db.open().get();

        assertEquals("WestCore-Stats", db.call(s -> Thread.currentThread().getName()).get());
        for (int i = 0; i < 200; i++) {
            int n = i;
            db.run(s -> s.upsertPlayer(UUID.randomUUID(), "P" + n, n, n));
        }
        db.shutdown(10);

        SqliteStatsStorage check = new SqliteStatsStorage(new File(dir, "stats.db"), "test", () -> false);
        check.open();
        assertEquals(200, check.countPlayers());
        check.close();
    }
}
