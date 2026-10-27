package com.westcube.westcore.core;

import com.westcube.westcore.WestCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un module = une section de config.yml ("<id>.enabled"), ses écouteurs, ses permissions
 * et ses sous-commandes de /westcore.
 *
 * Permissions : déclarées par clé dans le constructeur ; leur nom effectif se lit dans
 * "<id>.permissions.<clé>" et elles sont enregistrées auprès de Bukkit au démarrage du module.
 * Les tâches planifiées sont toutes annulées par WestCore à l'arrêt / au rechargement.
 */
public abstract class Module implements Listener {

    public record PermissionSpec(String key, String defaultName, PermissionDefault defaultValue, String description) {}

    /** Sous-commande "/westcore <name>" ; visible si le joueur a l'une des permissions (aucune = tout le monde). */
    public record CommandSpec(String name, String description, List<String> permissionKeys) {}

    protected final WestCore plugin;
    private final String id;
    private boolean enabled;

    private final Map<String, PermissionSpec> permissionSpecs = new LinkedHashMap<>();
    private final List<CommandSpec> commandSpecs = new ArrayList<>();
    private final List<Permission> registered = new ArrayList<>();
    private volatile Map<String, String> resolved = Map.of();

    protected Module(WestCore plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    public final String id() {
        return id;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final void start() {
        if (!plugin.getConfig().getBoolean(id + ".enabled", true)) {
            plugin.getLogger().info("Module " + id + " désactivé dans la config.");
            return;
        }
        enabled = true;
        registerPermissions();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        onEnable();
        plugin.getLogger().info("Module " + id + " démarré.");
    }

    public final void stop() {
        if (!enabled) return;
        try {
            onDisable();
        } finally {
            HandlerList.unregisterAll(this);
            unregisterPermissions();
            enabled = false;
        }
    }

    protected void onEnable() {}

    protected void onDisable() {}

    /** Sous-commande déclarée par ce module ; "name" est le nom de la sous-commande, args sans celle-ci. */
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        return false;
    }

    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        return List.of();
    }

    // ----------------------------------------------------------- déclarations

    protected final void permission(String key, String defaultName, PermissionDefault defaultValue, String description) {
        permissionSpecs.put(key, new PermissionSpec(key, defaultName, defaultValue, description));
    }

    protected final void command(String name, String description, String... permissionKeys) {
        commandSpecs.add(new CommandSpec(name, description, List.of(permissionKeys)));
    }

    public final List<CommandSpec> commands() {
        return Collections.unmodifiableList(commandSpecs);
    }

    public final Map<String, PermissionSpec> permissionSpecs() {
        return Collections.unmodifiableMap(permissionSpecs);
    }

    // ------------------------------------------------------------ permissions

    /** Nom effectif de la permission (config, sinon valeur par défaut). */
    public final String perm(String key) {
        String name = resolved.get(key);
        if (name != null) return name;
        PermissionSpec spec = permissionSpecs.get(key);
        if (spec == null) throw new IllegalArgumentException("Permission inconnue pour " + id + " : " + key);
        return configured(spec);
    }

    protected final boolean has(Permissible who, String key) {
        return who.hasPermission(perm(key));
    }

    private String configured(PermissionSpec spec) {
        String name = cfg().getString("permissions." + spec.key(), spec.defaultName());
        return name == null || name.isBlank() ? spec.defaultName() : name.trim();
    }

    private void registerPermissions() {
        PluginManager pm = Bukkit.getPluginManager();
        Map<String, String> map = new LinkedHashMap<>();
        for (PermissionSpec spec : permissionSpecs.values()) {
            String name = configured(spec);
            map.put(spec.key(), name);
            // Déjà déclarée (autre plugin, ou même nom pour deux clés) : on ne la touche pas
            if (pm.getPermission(name) != null) continue;
            Permission permission = new Permission(name, spec.description(), spec.defaultValue());
            pm.addPermission(permission);
            registered.add(permission);
        }
        resolved = map;
    }

    private void unregisterPermissions() {
        PluginManager pm = Bukkit.getPluginManager();
        for (Permission permission : registered) pm.removePermission(permission);
        registered.clear();
        resolved = Map.of();
    }

    // ---------------------------------------------------------------- config

    protected ConfigurationSection cfg() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(id);
        return section != null ? section : new MemoryConfiguration();
    }

    protected String raw(String messageKey) {
        return cfg().getString("messages." + messageKey, "");
    }

    /** Message préfixé du module, avec remplacement {clé} -> valeur. */
    protected Component msg(String messageKey, String... replacements) {
        return Text.mm(cfg().getString("prefix", "") + Text.fill(raw(messageKey), replacements));
    }

    protected Component noPermission() {
        return Text.mm(cfg().getString("prefix", "") + plugin.message("no-permission"));
    }

    protected Component global(String key) {
        return Text.mm(cfg().getString("prefix", "") + plugin.message(key));
    }

    /** Commande à afficher dans les messages d'aide : le raccourci s'il existe, sinon "/wc <sous-commande>". */
    protected String label(String subcommand) {
        return plugin.commands().label(subcommand);
    }

    // ------------------------------------------------------------- scheduler

    protected BukkitTask later(Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    protected BukkitTask repeat(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }
}
