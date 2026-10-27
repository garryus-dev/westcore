package com.westcube.westcore.frames;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Module;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

import java.util.EnumSet;
import java.util.Set;

/**
 * Accroupi + clic droit sur un cadre : le rend invisible / visible (ex-InvisibleFrames).
 * Les plugins de protection passent avant (priorité HIGHEST, événements annulés ignorés).
 */
public class FramesModule extends Module {

    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private String permission;
    private boolean lockFrame;
    private boolean toggleEmpty;

    public FramesModule(WestCore plugin) {
        super(plugin, "frames");
        permission("toggle", "invisibleframes.toggleframes", PermissionDefault.OP, "Rendre un cadre invisible");
    }

    @Override
    protected void onEnable() {
        permission = perm("toggle");
        lockFrame = cfg().getBoolean("lock-frame", true);
        toggleEmpty = cfg().getBoolean("toggle-empty", false);
        blacklist.clear();
        for (String name : cfg().getStringList("blacklisted-items")) {
            Material material = Material.matchMaterial(name);
            if (material != null) blacklist.add(material);
            else plugin.getLogger().warning("frames.blacklisted-items : matériau inconnu " + name);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof ItemFrame frame)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !p.hasPermission(permission)) return;

        ItemStack item = frame.getItem();
        boolean empty = item.getType().isAir();
        // Un cadre invisible doit toujours pouvoir redevenir visible
        if (frame.isVisible()) {
            if (empty && !toggleEmpty) return;
            if (!empty && blacklist.contains(item.getType())) return;
        }

        e.setCancelled(true);
        boolean visible = !frame.isVisible();
        frame.setVisible(visible);
        // Verrouillé tant qu'il est invisible : pas de cadres invisibles oubliés, pas de physique
        if (lockFrame) frame.setFixed(!visible);
    }
}
