package com.westcube.westcore.core.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textures de skin pour les têtes des menus. Un client n'affiche pas le skin d'une tête
 * qui n'a que l'UUID et le pseudo : il faut lui envoyer la propriété "textures".
 * Joueurs en ligne : immédiat. Hors ligne : PlayerProfile.update() (asynchrone, cache Paper puis Mojang),
 * gardé en mémoire ensuite.
 */
public final class Heads {

    private static final Map<UUID, Set<ProfileProperty>> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<Set<ProfileProperty>>> PENDING = new ConcurrentHashMap<>();

    private Heads() {}

    private static boolean hasTextures(Set<ProfileProperty> properties) {
        return properties.stream().anyMatch(p -> p.getName().equals("textures"));
    }

    /** Textures déjà connues (thread principal), null sinon. */
    public static Set<ProfileProperty> cached(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Set<ProfileProperty> properties = Set.copyOf(online.getPlayerProfile().getProperties());
            if (hasTextures(properties)) {
                TEXTURES.put(uuid, properties);
                return properties;
            }
        }
        return TEXTURES.get(uuid);
    }

    /**
     * Résout les textures sans bloquer (appel depuis le thread principal) ;
     * le futur peut se terminer sur un autre thread, avec null si le skin est introuvable.
     */
    public static CompletableFuture<Set<ProfileProperty>> resolve(UUID uuid, String name) {
        Set<ProfileProperty> known = cached(uuid);
        if (known != null) return CompletableFuture.completedFuture(known);
        CompletableFuture<Set<ProfileProperty>> running = PENDING.get(uuid);
        if (running != null) return running;

        PlayerProfile profile = Bukkit.createProfile(uuid, name);
        CompletableFuture<Set<ProfileProperty>> future = profile.update().handle((updated, error) -> {
            if (error != null || updated == null) return null;
            Set<ProfileProperty> properties = Set.copyOf(updated.getProperties());
            if (!hasTextures(properties)) return null;
            TEXTURES.put(uuid, properties);
            return properties;
        });
        PENDING.put(uuid, future);
        future.whenComplete((result, error) -> PENDING.remove(uuid, future));
        return future;
    }
}
