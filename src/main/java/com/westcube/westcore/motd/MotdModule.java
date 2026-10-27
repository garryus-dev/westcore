package com.westcube.westcore.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Text;
import com.westcube.westcore.core.Module;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MOTD, icône et nombre de places affichés dans la liste des serveurs (ex-MiniMOTD).
 * Priorité basse : Maintenance garde la main sur le MOTD pendant une maintenance.
 */
public class MotdModule extends Module {

    private final List<Component> motds = new ArrayList<>();
    private CachedServerIcon icon;
    private int maxPlayers;

    public MotdModule(WestCore plugin) {
        super(plugin, "motd");
    }

    @Override
    protected void onEnable() {
        motds.clear();
        for (Map<?, ?> entry : cfg().getMapList("motds")) {
            String line1 = String.valueOf(entry.get("line1") == null ? "" : entry.get("line1"));
            String line2 = String.valueOf(entry.get("line2") == null ? "" : entry.get("line2"));
            motds.add(Text.mm(line1 + "<reset><newline>" + line2));
        }
        maxPlayers = cfg().getInt("max-players", 0);

        icon = null;
        String iconName = cfg().getString("icon", "");
        if (iconName != null && !iconName.isBlank()) {
            File file = new File(plugin.getDataFolder(), iconName);
            if (!file.exists() && plugin.getResource(iconName) != null) plugin.saveResource(iconName, false);
            try {
                icon = Bukkit.loadServerIcon(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Icône " + iconName + " illisible (PNG 64x64 attendu) : " + e.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPing(PaperServerListPingEvent e) {
        if (!motds.isEmpty()) e.motd(motds.get(ThreadLocalRandom.current().nextInt(motds.size())));
        if (icon != null) e.setServerIcon(icon);
        if (maxPlayers > 0) e.setMaxPlayers(maxPlayers);
    }
}
