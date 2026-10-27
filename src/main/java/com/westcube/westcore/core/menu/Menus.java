package com.westcube.westcore.core.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Écouteur unique des menus WestCore (enregistré une fois, hors modules). */
public final class Menus implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        // Rien ne bouge, ni dans le menu ni depuis l'inventaire du joueur (shift-clic, double-clic...)
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getWhoClicked() instanceof Player p) menu.click(p, e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu menu && e.getPlayer() instanceof Player p) menu.onClose(p);
    }

    /** Ferme tous les menus ouverts (rechargement, arrêt). */
    public static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) p.closeInventory();
        }
    }
}
