package com.westcube.westcore.placeholders;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Text;
import com.westcube.westcore.dragon.DragonModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %westcube_endstatus%, %westcube_endcooldown%, %westcube_dragonkills%,
 * %westcube_keepinv%, %westcube_haslastpos_<monde>%, %westcube_afk%
 * (mêmes noms que les anciens placeholders Skript : menus et TAB inchangés).
 */
public class WestCubeExpansion extends PlaceholderExpansion {

    private final WestCore plugin;

    public WestCubeExpansion(WestCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "westcube";
    }

    @Override
    public String getAuthor() {
        return "Diably12";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String id = params.toLowerCase();

        DragonModule dragon = plugin.dragon();
        switch (id) {
            case "endstatus":
                if (!dragon.isEnabled()) return "";
                return conf(dragon.isLocked() ? "dragon.placeholder.unavailable" : "dragon.placeholder.available");
            case "endcooldown":
                if (!dragon.isEnabled()) return "";
                long mins = dragon.minutesLeft();
                return mins > 0 ? Text.fill(conf("dragon.placeholder.cooldown"), "minutes", String.valueOf(mins)) : "";
            case "dragonkills":
                if (player == null || !dragon.isEnabled()) return "0";
                return String.valueOf(dragon.kills(player.getUniqueId()));
            case "keepinv":
                if (!plugin.keepInv().isEnabled()) return "";
                if (player == null) return conf("keepinv.placeholder.off");
                return plugin.keepInv().placeholder(player.getUniqueId());
            case "afk":
                if (player == null || !plugin.afk().isEnabled()) return conf("afk.placeholder.not-afk");
                return conf(plugin.afk().isAfk(player.getUniqueId()) ? "afk.placeholder.afk" : "afk.placeholder.not-afk");
            default:
                break;
        }

        if (id.startsWith("haslastpos_")) {
            // le nom du monde garde sa casse d'origine
            String world = params.substring("haslastpos_".length());
            if (player == null || !plugin.lastPos().isEnabled()) return "0";
            return plugin.lastPos().has(player.getUniqueId(), world) ? "1" : "0";
        }
        return null;
    }

    private String conf(String path) {
        return plugin.getConfig().getString(path, "");
    }
}
