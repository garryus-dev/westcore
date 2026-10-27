package com.westcube.westcore.stats.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * StatsStorage sur un fichier SQLite (driver fourni par Paper).
 * Une seule connexion, utilisée uniquement par le thread de StatsDatabase.
 */
public final class SqliteStatsStorage implements StatsStorage {

    private static final int SCHEMA_VERSION = 1;

    private final File file;
    private final String serverId;
    private final BooleanSupplier isMainThread;
    private Connection connection;

    /** isMainThread : Bukkit::isPrimaryThread en jeu ; toute requête depuis le thread principal est refusée. */
    public SqliteStatsStorage(File file, String serverId, BooleanSupplier isMainThread) {
        this.file = file;
        this.serverId = serverId;
        this.isMainThread = isMainThread;
    }

    @Override
    public String serverId() {
        return serverId;
    }

    private Connection db() {
        if (isMainThread.getAsBoolean()) {
            throw new IllegalStateException("Accès à la base des stats depuis le thread principal");
        }
        if (connection == null) throw new StorageException("Base des stats non ouverte");
        return connection;
    }

    // ------------------------------------------------------------ ouverture

    @Override
    public void open() {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = db().createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
        } catch (ClassNotFoundException e) {
            throw new StorageException("Driver SQLite introuvable (fourni normalement par Paper)", e);
        } catch (SQLException e) {
            throw new StorageException("Ouverture impossible de " + file.getName(), e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = db().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        int version = meta("schema_version").map(Integer::parseInt).orElse(0);
        if (version >= SCHEMA_VERSION) return;

        db().setAutoCommit(false);
        try (Statement st = db().createStatement()) {
            if (version < 1) {
                st.execute("""
                        CREATE TABLE players (
                            server_id  TEXT    NOT NULL,
                            uuid       TEXT    NOT NULL,
                            name       TEXT    NOT NULL,
                            first_seen INTEGER NOT NULL,
                            last_seen  INTEGER NOT NULL,
                            PRIMARY KEY (server_id, uuid))""");
                st.execute("CREATE INDEX idx_players_first ON players(server_id, first_seen)");
                st.execute("""
                        CREATE TABLE sessions (
                            id             INTEGER PRIMARY KEY AUTOINCREMENT,
                            server_id      TEXT    NOT NULL,
                            uuid           TEXT    NOT NULL,
                            name           TEXT    NOT NULL,
                            start_ms       INTEGER NOT NULL,
                            end_ms         INTEGER,
                            hostname       TEXT,
                            client_version TEXT,
                            protocol       INTEGER,
                            client_brand   TEXT,
                            locale         TEXT,
                            afk_ms         INTEGER,
                            quit_reason    TEXT)""");
                st.execute("CREATE INDEX idx_sessions_start ON sessions(server_id, start_ms)");
                st.execute("CREATE INDEX idx_sessions_player ON sessions(server_id, uuid, start_ms)");
                st.execute("CREATE INDEX idx_sessions_open ON sessions(server_id, end_ms)");
                st.execute("""
                        CREATE TABLE snapshots (
                            server_id   TEXT    NOT NULL,
                            ts          INTEGER NOT NULL,
                            online      INTEGER NOT NULL,
                            tps_1m      REAL    NOT NULL,
                            tps_5m      REAL    NOT NULL,
                            tps_15m     REAL    NOT NULL,
                            mspt_avg    REAL    NOT NULL,
                            mspt_max    REAL    NOT NULL,
                            ram_used_mb INTEGER NOT NULL,
                            ram_max_mb  INTEGER NOT NULL,
                            chunks      INTEGER NOT NULL,
                            entities    INTEGER NOT NULL,
                            ping_avg    REAL,
                            PRIMARY KEY (server_id, ts))""");
                st.execute("""
                        CREATE TABLE daily (
                            server_id         TEXT    NOT NULL,
                            day               TEXT    NOT NULL,
                            uniques           INTEGER NOT NULL,
                            new_players       INTEGER NOT NULL,
                            sessions          INTEGER NOT NULL,
                            peak_online       INTEGER NOT NULL,
                            play_ms           INTEGER NOT NULL,
                            afk_ms            INTEGER,
                            median_session_ms INTEGER NOT NULL,
                            avg_session_ms    INTEGER NOT NULL,
                            timeouts          INTEGER NOT NULL,
                            tps_avg           REAL,
                            tps_min           REAL,
                            mspt_avg          REAL,
                            mspt_max          REAL,
                            ram_used_avg_mb   INTEGER,
                            ram_max_mb        INTEGER,
                            chunks_avg        INTEGER,
                            entities_avg      INTEGER,
                            ping_avg          REAL,
                            computed_at       INTEGER NOT NULL,
                            PRIMARY KEY (server_id, day))""");
            }
            setMeta("schema_version", String.valueOf(SCHEMA_VERSION));
            db().commit();
        } catch (SQLException e) {
            db().rollback();
            throw e;
        } finally {
            db().setAutoCommit(true);
        }
    }

    @Override
    public void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // fermeture : rien à récupérer
        }
        connection = null;
    }

    // --------------------------------------------------------------- sessions

    @Override
    public long openSession(UUID uuid, String name, long startMs, String hostname,
                            String clientVersion, Integer protocol, String clientBrand, String locale) {
        String sql = """
                INSERT INTO sessions (server_id, uuid, name, start_ms, hostname, client_version, protocol, client_brand, locale)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = db().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            ps.setString(3, name);
            ps.setLong(4, startMs);
            ps.setString(5, hostname);
            ps.setString(6, clientVersion);
            setInt(ps, 7, protocol);
            ps.setString(8, clientBrand);
            ps.setString(9, locale);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new StorageException("Identifiant de session non renvoyé");
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new StorageException("openSession", e);
        }
    }

    @Override
    public void updateSessionClient(long sessionId, String clientBrand, String locale) {
        String sql = "UPDATE sessions SET client_brand = COALESCE(?, client_brand), locale = COALESCE(?, locale) WHERE id = ?";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, clientBrand);
            ps.setString(2, locale);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("updateSessionClient", e);
        }
    }

    @Override
    public void updateSessionAfk(Map<Long, Long> afkMsBySession) {
        if (afkMsBySession.isEmpty()) return;
        String sql = "UPDATE sessions SET afk_ms = ? WHERE id = ? AND end_ms IS NULL";
        inTransaction(() -> {
            try (PreparedStatement ps = db().prepareStatement(sql)) {
                for (Map.Entry<Long, Long> entry : afkMsBySession.entrySet()) {
                    setLong(ps, 1, entry.getValue());
                    ps.setLong(2, entry.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }, "updateSessionAfk");
    }

    @Override
    public void closeSession(long sessionId, long endMs, Long afkMs, String quitReason) {
        String sql = """
                UPDATE sessions SET end_ms = MAX(start_ms, ?),
                                    afk_ms = MIN(?, MAX(start_ms, ?) - start_ms),
                                    quit_reason = ?
                WHERE id = ? AND end_ms IS NULL""";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setLong(1, endMs);
            setLong(ps, 2, afkMs);
            ps.setLong(3, endMs);
            ps.setString(4, quitReason);
            ps.setLong(5, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("closeSession", e);
        }
    }

    @Override
    public int closeOrphanSessions(String quitReason) {
        int[] closed = {0};
        inTransaction(() -> {
            long lastSnapshot = lastSnapshot().map(SnapshotRecord::ts).orElse(0L);
            try (PreparedStatement ps = db().prepareStatement("""
                    UPDATE sessions SET end_ms = MAX(start_ms, ?),
                                        afk_ms = MIN(afk_ms, MAX(start_ms, ?) - start_ms),
                                        quit_reason = ?
                    WHERE server_id = ? AND end_ms IS NULL""")) {
                ps.setLong(1, lastSnapshot);
                ps.setLong(2, lastSnapshot);
                ps.setString(3, quitReason);
                ps.setString(4, serverId);
                closed[0] = ps.executeUpdate();
            }
            if (closed[0] == 0) return;
            // Dernière connexion des joueurs concernés = fin de leur dernière session
            try (PreparedStatement ps = db().prepareStatement("""
                    UPDATE players SET last_seen = MAX(last_seen, (
                        SELECT MAX(s.end_ms) FROM sessions s
                        WHERE s.server_id = players.server_id AND s.uuid = players.uuid AND s.quit_reason = ?))
                    WHERE server_id = ? AND uuid IN (
                        SELECT uuid FROM sessions WHERE server_id = ? AND quit_reason = ?)""")) {
                ps.setString(1, quitReason);
                ps.setString(2, serverId);
                ps.setString(3, serverId);
                ps.setString(4, quitReason);
                ps.executeUpdate();
            }
        }, "closeOrphanSessions");
        return closed[0];
    }

    @Override
    public List<SessionRecord> sessionsOverlapping(long fromMs, long toMs) {
        String sql = """
                SELECT * FROM sessions
                WHERE server_id = ? AND start_ms < ? AND (end_ms IS NULL OR end_ms > ?)
                ORDER BY start_ms""";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, toMs);
            ps.setLong(3, fromMs);
            return sessions(ps);
        } catch (SQLException e) {
            throw new StorageException("sessionsOverlapping", e);
        }
    }

    @Override
    public List<SessionRecord> sessionsOf(UUID uuid, int limit) {
        String sql = "SELECT * FROM sessions WHERE server_id = ? AND uuid = ? ORDER BY start_ms DESC LIMIT ?";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            ps.setInt(3, limit);
            return sessions(ps);
        } catch (SQLException e) {
            throw new StorageException("sessionsOf", e);
        }
    }

    @Override
    public Optional<Long> firstSessionStart() {
        try (PreparedStatement ps = db().prepareStatement("SELECT MIN(start_ms) FROM sessions WHERE server_id = ?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(getLong(rs, 1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("firstSessionStart", e);
        }
    }

    private static List<SessionRecord> sessions(PreparedStatement ps) throws SQLException {
        List<SessionRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SessionRecord(
                        rs.getLong("id"),
                        rs.getString("server_id"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getLong("start_ms"),
                        getLong(rs, "end_ms"),
                        rs.getString("hostname"),
                        rs.getString("client_version"),
                        getInt(rs, "protocol"),
                        rs.getString("client_brand"),
                        rs.getString("locale"),
                        getLong(rs, "afk_ms"),
                        rs.getString("quit_reason")));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- joueurs

    @Override
    public void upsertPlayer(UUID uuid, String name, long firstSeen, long lastSeen) {
        String sql = """
                INSERT INTO players (server_id, uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (server_id, uuid) DO UPDATE SET
                    name = excluded.name,
                    first_seen = MIN(players.first_seen, excluded.first_seen),
                    last_seen = MAX(players.last_seen, excluded.last_seen)""";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            ps.setString(3, name);
            ps.setLong(4, firstSeen);
            ps.setLong(5, lastSeen);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("upsertPlayer", e);
        }
    }

    @Override
    public Optional<PlayerRecord> player(UUID uuid) {
        try (PreparedStatement ps = db().prepareStatement("SELECT * FROM players WHERE server_id = ? AND uuid = ?")) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            List<PlayerRecord> list = players(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        } catch (SQLException e) {
            throw new StorageException("player", e);
        }
    }

    @Override
    public List<PlayerRecord> playersFirstSeenBetween(long fromMs, long toMs) {
        String sql = "SELECT * FROM players WHERE server_id = ? AND first_seen >= ? AND first_seen < ? ORDER BY first_seen";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            return players(ps);
        } catch (SQLException e) {
            throw new StorageException("playersFirstSeenBetween", e);
        }
    }

    @Override
    public int countPlayers() {
        try (PreparedStatement ps = db().prepareStatement("SELECT COUNT(*) FROM players WHERE server_id = ?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("countPlayers", e);
        }
    }

    @Override
    public List<PlayerSummary> playerSummaries(long sinceMs, long nowMs, PlayerSummary.Sort sort, int offset, int limit) {
        String order = switch (sort) {
            case PLAYTIME -> "play_ms DESC";
            case LAST_SEEN -> "p.last_seen DESC";
            case SESSIONS -> "sessions DESC";
            case NEWEST -> "p.first_seen DESC";
        };
        String sql = """
                SELECT p.uuid, p.name, p.first_seen, p.last_seen,
                       COUNT(s.id) AS sessions,
                       COALESCE(SUM(COALESCE(s.end_ms, ?) - s.start_ms), 0) AS play_ms
                FROM players p
                LEFT JOIN sessions s ON s.server_id = p.server_id AND s.uuid = p.uuid AND s.start_ms >= ?
                WHERE p.server_id = ?
                GROUP BY p.uuid
                ORDER BY %s, p.name COLLATE NOCASE
                LIMIT ? OFFSET ?""".formatted(order);
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setLong(2, sinceMs);
            ps.setString(3, serverId);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            List<PlayerSummary> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PlayerSummary(UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                            rs.getLong("first_seen"), rs.getLong("last_seen"),
                            rs.getInt("sessions"), rs.getLong("play_ms")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new StorageException("playerSummaries", e);
        }
    }

    private static List<PlayerRecord> players(PreparedStatement ps) throws SQLException {
        List<PlayerRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new PlayerRecord(rs.getString("server_id"), UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"), rs.getLong("first_seen"), rs.getLong("last_seen")));
            }
        }
        return out;
    }

    // -------------------------------------------------------------- snapshots

    @Override
    public void insertSnapshot(SnapshotRecord s) {
        String sql = """
                INSERT OR REPLACE INTO snapshots (server_id, ts, online, tps_1m, tps_5m, tps_15m, mspt_avg, mspt_max,
                                                  ram_used_mb, ram_max_mb, chunks, entities, ping_avg)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, s.ts());
            ps.setInt(3, s.online());
            ps.setDouble(4, s.tps1m());
            ps.setDouble(5, s.tps5m());
            ps.setDouble(6, s.tps15m());
            ps.setDouble(7, s.msptAvg());
            ps.setDouble(8, s.msptMax());
            ps.setLong(9, s.ramUsedMb());
            ps.setLong(10, s.ramMaxMb());
            ps.setInt(11, s.chunks());
            ps.setInt(12, s.entities());
            setDouble(ps, 13, s.pingAvg());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("insertSnapshot", e);
        }
    }

    @Override
    public List<SnapshotRecord> snapshotsBetween(long fromMs, long toMs) {
        String sql = "SELECT * FROM snapshots WHERE server_id = ? AND ts >= ? AND ts < ? ORDER BY ts";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            return snapshots(ps);
        } catch (SQLException e) {
            throw new StorageException("snapshotsBetween", e);
        }
    }

    @Override
    public Optional<SnapshotRecord> lastSnapshot() {
        String sql = "SELECT * FROM snapshots WHERE server_id = ? ORDER BY ts DESC LIMIT 1";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            List<SnapshotRecord> list = snapshots(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        } catch (SQLException e) {
            throw new StorageException("lastSnapshot", e);
        }
    }

    @Override
    public int purgeSnapshotsBefore(long ts) {
        try (PreparedStatement ps = db().prepareStatement("DELETE FROM snapshots WHERE server_id = ? AND ts < ?")) {
            ps.setString(1, serverId);
            ps.setLong(2, ts);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("purgeSnapshotsBefore", e);
        }
    }

    private static List<SnapshotRecord> snapshots(PreparedStatement ps) throws SQLException {
        List<SnapshotRecord> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SnapshotRecord(
                        rs.getString("server_id"),
                        rs.getLong("ts"),
                        rs.getInt("online"),
                        rs.getDouble("tps_1m"),
                        rs.getDouble("tps_5m"),
                        rs.getDouble("tps_15m"),
                        rs.getDouble("mspt_avg"),
                        rs.getDouble("mspt_max"),
                        rs.getLong("ram_used_mb"),
                        rs.getLong("ram_max_mb"),
                        rs.getInt("chunks"),
                        rs.getInt("entities"),
                        getDouble(rs, "ping_avg")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------- journalier

    @Override
    public void upsertDaily(DailyRecord d) {
        String sql = """
                INSERT OR REPLACE INTO daily (server_id, day, uniques, new_players, sessions, peak_online, play_ms, afk_ms,
                    median_session_ms, avg_session_ms, timeouts, tps_avg, tps_min, mspt_avg, mspt_max,
                    ram_used_avg_mb, ram_max_mb, chunks_avg, entities_avg, ping_avg, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, d.day().toString());
            ps.setInt(3, d.uniques());
            ps.setInt(4, d.newPlayers());
            ps.setInt(5, d.sessions());
            ps.setInt(6, d.peakOnline());
            ps.setLong(7, d.playMs());
            setLong(ps, 8, d.afkMs());
            ps.setLong(9, d.medianSessionMs());
            ps.setLong(10, d.avgSessionMs());
            ps.setInt(11, d.timeouts());
            setDouble(ps, 12, d.tpsAvg());
            setDouble(ps, 13, d.tpsMin());
            setDouble(ps, 14, d.msptAvg());
            setDouble(ps, 15, d.msptMax());
            setLong(ps, 16, d.ramUsedAvgMb());
            setLong(ps, 17, d.ramMaxMb());
            setInt(ps, 18, d.chunksAvg());
            setInt(ps, 19, d.entitiesAvg());
            setDouble(ps, 20, d.pingAvg());
            ps.setLong(21, d.computedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("upsertDaily", e);
        }
    }

    @Override
    public List<DailyRecord> daily(LocalDate from, LocalDate to) {
        String sql = "SELECT * FROM daily WHERE server_id = ? AND day >= ? AND day <= ? ORDER BY day";
        try (PreparedStatement ps = db().prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            List<DailyRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DailyRecord(
                            rs.getString("server_id"),
                            LocalDate.parse(rs.getString("day")),
                            rs.getInt("uniques"),
                            rs.getInt("new_players"),
                            rs.getInt("sessions"),
                            rs.getInt("peak_online"),
                            rs.getLong("play_ms"),
                            getLong(rs, "afk_ms"),
                            rs.getLong("median_session_ms"),
                            rs.getLong("avg_session_ms"),
                            rs.getInt("timeouts"),
                            getDouble(rs, "tps_avg"),
                            getDouble(rs, "tps_min"),
                            getDouble(rs, "mspt_avg"),
                            getDouble(rs, "mspt_max"),
                            getLong(rs, "ram_used_avg_mb"),
                            getLong(rs, "ram_max_mb"),
                            getInt(rs, "chunks_avg"),
                            getInt(rs, "entities_avg"),
                            getDouble(rs, "ping_avg"),
                            rs.getLong("computed_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new StorageException("daily", e);
        }
    }

    @Override
    public Optional<LocalDate> lastDailyDay() {
        try (PreparedStatement ps = db().prepareStatement("SELECT MAX(day) FROM daily WHERE server_id = ?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                String day = rs.next() ? rs.getString(1) : null;
                return day == null ? Optional.empty() : Optional.of(LocalDate.parse(day));
            }
        } catch (SQLException e) {
            throw new StorageException("lastDailyDay", e);
        }
    }

    @Override
    public void deleteDaily() {
        try (PreparedStatement ps = db().prepareStatement("DELETE FROM daily WHERE server_id = ?")) {
            ps.setString(1, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("deleteDaily", e);
        }
    }

    // ------------------------------------------------------------------ méta

    @Override
    public Optional<String> meta(String key) {
        try (PreparedStatement ps = db().prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("meta", e);
        }
    }

    @Override
    public void setMeta(String key, String value) {
        try (PreparedStatement ps = db().prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("setMeta", e);
        }
    }

    // ------------------------------------------------------------ utilitaires

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work, String what) {
        Connection c = db();
        try {
            c.setAutoCommit(false);
            try {
                work.run();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException(what, e);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setLong(index, value);
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) ps.setNull(index, Types.REAL);
        else ps.setDouble(index, value);
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Long getLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
