package com.westcube.westcore.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fichier YAML de données (plugins/WestCore/data/*.yml).
 * La sérialisation se fait sur le thread appelant, l'écriture disque en asynchrone ;
 * une sauvegarde plus ancienne ne peut jamais écraser une plus récente.
 */
public final class DataFile {

    private final Plugin plugin;
    private final File file;
    private final AtomicLong version = new AtomicLong();
    private long written;

    public DataFile(Plugin plugin, String name) {
        this.plugin = plugin;
        this.file = new File(new File(plugin.getDataFolder(), "data"), name);
    }

    public YamlConfiguration load() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().severe("Lecture impossible de " + file.getName() + " : " + e.getMessage());
            }
        }
        return yaml;
    }

    public void saveAsync(YamlConfiguration yaml) {
        String data = yaml.saveToString();
        long v = version.incrementAndGet();
        if (!plugin.isEnabled()) {
            write(data, v);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data, v));
    }

    public void saveSync(YamlConfiguration yaml) {
        write(yaml.saveToString(), version.incrementAndGet());
    }

    private synchronized void write(String data, long v) {
        if (v < written) return;
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            written = v;
        } catch (IOException e) {
            plugin.getLogger().severe("Écriture impossible de " + file.getName() + " : " + e.getMessage());
        }
    }
}
