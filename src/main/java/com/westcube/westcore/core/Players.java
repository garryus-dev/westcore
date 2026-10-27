package com.westcube.westcore.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

public final class Players {

    private Players() {}

    /** Joueur en ligne, sinon déjà venu sur le serveur (sans requête web à Mojang). Null sinon. */
    public static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) return cached;
        return null;
    }

    public static List<String> onlineNames(String prefix) {
        String p = prefix.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(p))
                .sorted()
                .toList();
    }
}
