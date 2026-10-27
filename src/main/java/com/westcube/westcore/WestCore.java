package com.westcube.westcore;

import com.westcube.westcore.api.ApiModule;
import com.westcube.westcore.afk.AfkModule;
import com.westcube.westcore.core.Commands;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.menu.Menus;
import com.westcube.westcore.dragon.DragonModule;
import com.westcube.westcore.frames.FramesModule;
import com.westcube.westcore.keepinv.KeepInvModule;
import com.westcube.westcore.lastpos.LastPosModule;
import com.westcube.westcore.motd.MotdModule;
import com.westcube.westcore.placeholders.WestCubeExpansion;
import com.westcube.westcore.staffcmd.StaffCmdModule;
import com.westcube.westcore.stats.StatsModule;
import com.westcube.westcore.tab.TabModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class WestCore extends JavaPlugin {

    /** Sections que l'utilisateur peut vider : leurs clés par défaut ne sont ajoutées qu'à leur création. */
    private static final String[] USER_SECTIONS = {"core.aliases"};

    /** Anciennes clés de permission -> nouvel emplacement (repris une fois, puis supprimés). */
    private static final Map<String, String> LEGACY_KEYS = Map.of(
            "frames.permission", "frames.permissions.toggle",
            "tab.staff-permission", "tab.permissions.staff",
            "tab.scoreboard.toggle-permission", "tab.permissions.scoreboard");

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Commands commands = new Commands(this);

    private AfkModule afk;
    private DragonModule dragon;
    private KeepInvModule keepInv;
    private LastPosModule lastPos;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyKeys();
        mergeNewDefaults();

        add(new CoreModule(this));
        add(new ApiModule(this));
        afk = add(new AfkModule(this));
        dragon = add(new DragonModule(this));
        keepInv = add(new KeepInvModule(this));
        lastPos = add(new LastPosModule(this));
        add(new StaffCmdModule(this));
        add(new FramesModule(this));
        add(new MotdModule(this));
        add(new TabModule(this));
        add(new StatsModule(this));

        PluginCommand root = getCommand("westcore");
        if (root != null) {
            root.setExecutor(commands);
            root.setTabCompleter(commands);
        }
        Bukkit.getPluginManager().registerEvents(new Menus(), this);

        startModules();
        commands.reloadAliases();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new WestCubeExpansion(this).register();
            getLogger().info("Placeholders %westcube_*% enregistrés.");
        }
    }

    @Override
    public void onDisable() {
        Menus.closeAll();
        commands.unregisterAliases();
        stopModules();
    }

    /** Déplace les clés de permission des anciennes versions vers "<module>.permissions". */
    private void migrateLegacyKeys() {
        boolean changed = false;
        for (Map.Entry<String, String> entry : LEGACY_KEYS.entrySet()) {
            if (!getConfig().isSet(entry.getKey())) continue;
            if (!getConfig().isSet(entry.getValue())) getConfig().set(entry.getValue(), getConfig().getString(entry.getKey()));
            getConfig().set(entry.getKey(), null);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Anciennes clés de permission déplacées vers <module>.permissions dans config.yml.");
        }
    }

    /** Ajoute au config.yml existant les sections apparues dans une nouvelle version du jar. */
    private void mergeNewDefaults() {
        InputStream resource = getResource("config.yml");
        if (resource == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || getConfig().isSet(key) || insideExistingUserSection(key)) continue;
            getConfig().set(key, defaults.get(key));
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Nouvelles options ajoutées à config.yml.");
        }
    }

    private boolean insideExistingUserSection(String key) {
        for (String section : USER_SECTIONS) {
            // isSet ne lit que le fichier (pas les valeurs par défaut du jar)
            if (key.startsWith(section + ".") && getConfig().isSet(section)) return true;
        }
        return false;
    }

    private <T extends Module> T add(T module) {
        modules.put(module.id(), module);
        commands.add(module);
        return module;
    }

    private void startModules() {
        for (Module module : modules.values()) {
            try {
                module.start();
            } catch (Exception e) {
                getLogger().severe("Impossible de démarrer le module " + module.id() + " : " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void stopModules() {
        for (Module module : modules.values()) {
            try {
                module.stop();
            } catch (Exception e) {
                getLogger().severe("Erreur à l'arrêt du module " + module.id() + " : " + e.getMessage());
            }
        }
        Bukkit.getScheduler().cancelTasks(this);
    }

    public void reload() {
        Menus.closeAll();
        stopModules();
        reloadConfig();
        startModules();
        commands.reloadAliases();
    }

    public String message(String key) {
        return getConfig().getString("messages." + key, "");
    }

    public Commands commands() { return commands; }
    public Collection<Module> modules() { return Collections.unmodifiableCollection(modules.values()); }

    public AfkModule afk() { return afk; }
    public DragonModule dragon() { return dragon; }
    public KeepInvModule keepInv() { return keepInv; }
    public LastPosModule lastPos() { return lastPos; }
}
