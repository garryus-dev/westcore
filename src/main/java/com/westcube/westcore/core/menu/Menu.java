package com.westcube.westcore.core.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Menu coffre : les items ne peuvent jamais être pris ou déposés, chaque slot peut porter une action.
 * À manipuler uniquement sur le thread principal (Menus route les clics).
 */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface Action {
        void run(Player player, ClickType click);
    }

    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();

    protected Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public final @NotNull Inventory getInventory() {
        return inventory;
    }

    public final int size() {
        return inventory.getSize();
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected final void set(int slot, ItemStack item, Action action) {
        inventory.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
    }

    protected final void clear() {
        inventory.clear();
        actions.clear();
    }

    protected final void clear(int fromSlot, int toSlotExclusive) {
        for (int i = fromSlot; i < toSlotExclusive; i++) set(i, null);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    final void click(Player player, int slot, ClickType click) {
        Action action = actions.get(slot);
        if (action != null) action.run(player, click);
    }

    /** Appelé quand un joueur ferme le menu. */
    protected void onClose(Player player) {}

    /** Vrai si au moins un joueur regarde encore ce menu (pour ignorer les chargements arrivés trop tard). */
    public final boolean isViewed() {
        return !inventory.getViewers().isEmpty();
    }
}
