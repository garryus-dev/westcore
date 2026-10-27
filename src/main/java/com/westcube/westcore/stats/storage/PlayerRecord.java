package com.westcube.westcore.stats.storage;

import java.util.UUID;

/** Joueur vu sur ce serveur. firstSeen = première connexion connue de Bukkit (même avant le module). */
public record PlayerRecord(String serverId, UUID uuid, String name, long firstSeen, long lastSeen) {}
