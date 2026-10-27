package com.westcube.westcore.stats.storage;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stockage des statistiques d'un serveur (server_id fixé à la construction).
 * Toutes les méthodes sont bloquantes : elles ne s'appellent que depuis le thread
 * de StatsDatabase, jamais depuis le thread principal.
 * Horodatages en millisecondes UTC ; les jours sont ceux du fuseau configuré.
 */
public interface StatsStorage {

    String serverId();

    /** Ouvre la connexion et crée / met à jour le schéma. */
    void open();

    void close();

    // --------------------------------------------------------------- sessions

    /** Crée une session ouverte et renvoie son identifiant. */
    long openSession(UUID uuid, String name, long startMs, String hostname,
                     String clientVersion, Integer protocol, String clientBrand, String locale);

    /** Marque et langue du client, souvent connues quelques secondes après le join. */
    void updateSessionClient(long sessionId, String clientBrand, String locale);

    /** Temps AFK courant des sessions ouvertes (conservé en cas de crash). */
    void updateSessionAfk(Map<Long, Long> afkMsBySession);

    void closeSession(long sessionId, long endMs, Long afkMs, String quitReason);

    /**
     * Clôture les sessions restées ouvertes (crash) à l'horodatage du dernier snapshot,
     * jamais avant leur début. Renvoie le nombre de sessions clôturées.
     */
    int closeOrphanSessions(String quitReason);

    /** Sessions qui chevauchent [fromMs, toMs[ (les sessions ouvertes chevauchent tout ce qui suit leur début). */
    List<SessionRecord> sessionsOverlapping(long fromMs, long toMs);

    /** Dernières sessions d'un joueur, la plus récente d'abord. */
    List<SessionRecord> sessionsOf(UUID uuid, int limit);

    Optional<Long> firstSessionStart();

    // ---------------------------------------------------------------- joueurs

    /** Crée le joueur ou met à jour pseudo / dernière connexion (firstSeen n'est jamais repoussé). */
    void upsertPlayer(UUID uuid, String name, long firstSeen, long lastSeen);

    Optional<PlayerRecord> player(UUID uuid);

    List<PlayerRecord> playersFirstSeenBetween(long fromMs, long toMs);

    int countPlayers();

    /** Joueurs avec leur activité sur les sessions commencées depuis sinceMs. */
    List<PlayerSummary> playerSummaries(long sinceMs, long nowMs, PlayerSummary.Sort sort, int offset, int limit);

    // -------------------------------------------------------------- snapshots

    void insertSnapshot(SnapshotRecord snapshot);

    List<SnapshotRecord> snapshotsBetween(long fromMs, long toMs);

    Optional<SnapshotRecord> lastSnapshot();

    int purgeSnapshotsBefore(long ts);

    // ------------------------------------------------------------- journalier

    void upsertDaily(DailyRecord record);

    List<DailyRecord> daily(LocalDate from, LocalDate to);

    Optional<LocalDate> lastDailyDay();

    void deleteDaily();

    // ------------------------------------------------------------------ méta

    Optional<String> meta(String key);

    void setMeta(String key, String value);
}
