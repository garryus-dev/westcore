package com.westcube.westcore.core.menu;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.westcube.westcore.core.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Construction d'items de menu : Items.of(Material.CLOCK).name("<gold>Titre").lore("ligne").glint(true).build() */
public final class Items {

    private final ItemStack item;
    private Component name;
    private final List<Component> lore = new ArrayList<>();
    private boolean glint;
    private int amount = 1;

    private Items(ItemStack item) {
        this.item = item;
    }

    public static Items of(Material material) {
        return new Items(new ItemStack(material));
    }

    public static Items of(ItemStack base) {
        return new Items(base.clone());
    }

    /**
     * Tête de joueur avec son skin s'il est déjà connu (Heads), sinon tête générique :
     * appeler Heads.resolve puis reconstruire l'item pour afficher le skin.
     */
    public static Items head(UUID uuid, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile().uuid(uuid).name(name);
        Set<ProfileProperty> textures = Heads.cached(uuid);
        if (textures != null) profile.addProperties(textures);
        item.setData(DataComponentTypes.PROFILE, profile);
        return new Items(item);
    }

    /** MiniMessage, sans italique par défaut. */
    public Items name(String miniMessage) {
        this.name = plain(Text.mm(miniMessage));
        return this;
    }

    public Items lore(String... miniMessageLines) {
        for (String line : miniMessageLines) lore.add(plain(Text.mm(line)));
        return this;
    }

    public Items lore(List<String> miniMessageLines) {
        return lore(miniMessageLines.toArray(String[]::new));
    }

    public Items glint(boolean glint) {
        this.glint = glint;
        return this;
    }

    /** Quantité affichée (1 à 99). */
    public Items amount(int amount) {
        this.amount = Math.clamp(amount, 1, 99);
        return this;
    }

    public ItemStack build() {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            if (glint) meta.setEnchantmentGlintOverride(true);
            if (amount > item.getMaxStackSize()) meta.setMaxStackSize(Math.min(99, amount));
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        // Après la taille de pile maximale, sinon la quantité serait tronquée
        item.setAmount(amount);
        return item;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
