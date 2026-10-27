package com.westcube.westcore.core;

import com.westcube.westcore.WestCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Toutes les commandes WestCore sont des sous-commandes de /westcore (alias /wc).
 * "core.aliases" dans config.yml crée des commandes directes :
 *   nom: "sous-commande [arguments fixes]"   ex.  afk: "afk"   ou   export7: "stats export 7d"
 * Les raccourcis sont recréés à chaque /westcore reload.
 */
public final class Commands implements TabExecutor {

    private static final String NAMESPACE = "westcore";
    private static final Pattern ALIAS_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private record Entry(Module module, Module.CommandSpec spec) {}

    private final WestCore plugin;
    private final Map<String, Entry> subcommands = new LinkedHashMap<>();
    private final Map<String, Alias> aliases = new LinkedHashMap<>();

    public Commands(WestCore plugin) {
        this.plugin = plugin;
    }

    public void add(Module module) {
        for (Module.CommandSpec spec : module.commands()) {
            Entry previous = subcommands.putIfAbsent(spec.name(), new Entry(module, spec));
            if (previous != null) {
                plugin.getLogger().warning("Sous-commande " + spec.name() + " déclarée par " + previous.module().id()
                        + " et " + module.id() + " : seule la première est gardée.");
            }
        }
    }

    // ------------------------------------------------------------- exécution

    private boolean canUse(CommandSender sender, Entry entry) {
        List<String> keys = entry.spec().permissionKeys();
        if (keys.isEmpty()) return true;
        for (String key : keys) {
            if (sender.hasPermission(entry.module().perm(key))) return true;
        }
        return false;
    }

    /** Permissions de la sous-commande au format Bukkit ("a;b"), null si aucune. */
    private static String permissionString(Entry entry) {
        List<String> keys = entry.spec().permissionKeys();
        if (keys.isEmpty()) return null;
        return String.join(";", keys.stream().map(k -> entry.module().perm(k)).toList());
    }

    private void dispatch(CommandSender sender, String name, String[] args) {
        Entry entry = subcommands.get(name);
        if (entry == null) {
            help(sender);
            return;
        }
        if (!entry.module().isEnabled()) {
            sender.sendMessage(Text.mm(plugin.message("module-disabled")));
            return;
        }
        if (!canUse(sender, entry)) {
            sender.sendMessage(Text.mm(plugin.message("no-permission")));
            return;
        }
        if (!entry.module().onCommand(sender, name, args)) help(sender);
    }

    private List<String> complete(CommandSender sender, String name, String[] args) {
        Entry entry = subcommands.get(name);
        if (entry == null || !entry.module().isEnabled() || !canUse(sender, entry)) return List.of();
        return entry.module().onTabComplete(sender, name, args);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.mm("<gray>Commandes WestCore :"));
        boolean any = false;
        for (Entry entry : subcommands.values()) {
            if (!entry.module().isEnabled() || !canUse(sender, entry)) continue;
            sender.sendMessage(Text.mm(" <dark_gray>•</dark_gray> <white>" + Text.escape(label(entry.spec().name()))
                    + "</white> <gray>- " + Text.escape(entry.spec().description())));
            any = true;
        }
        if (!any) sender.sendMessage(Text.mm(" <dark_gray>•</dark_gray> <gray>Aucune commande disponible."));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        dispatch(sender, args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subcommands.values().stream()
                    .filter(e -> e.module().isEnabled() && canUse(sender, e))
                    .map(e -> e.spec().name())
                    .filter(n -> n.startsWith(prefix))
                    .toList();
        }
        return complete(sender, args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
    }

    // -------------------------------------------------------------- raccourcis

    /** Commande directe créée depuis core.aliases. */
    private final class Alias extends Command {
        final String target;
        final String[] fixedArgs;

        Alias(String name, String target, String[] fixedArgs, Entry entry) {
            super(name);
            this.target = target;
            this.fixedArgs = fixedArgs;
            setDescription(entry.spec().description());
            setPermission(permissionString(entry));
        }

        private String[] merge(String[] args) {
            String[] all = Arrays.copyOf(fixedArgs, fixedArgs.length + args.length);
            System.arraycopy(args, 0, all, fixedArgs.length, args.length);
            return all;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, String @NotNull [] args) {
            dispatch(sender, target, merge(args));
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, String @NotNull [] args) {
            return complete(sender, target, merge(args));
        }
    }

    /** Recrée les raccourcis de core.aliases (à appeler après le démarrage des modules). */
    public void reloadAliases() {
        unregisterAliases();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("core.aliases");
        if (section != null) {
            CommandMap map = Bukkit.getCommandMap();
            for (String name : section.getKeys(false)) {
                String alias = name.toLowerCase(Locale.ROOT);
                String value = section.getString(name, "").trim();
                if (value.isEmpty()) continue;
                if (!ALIAS_NAME.matcher(alias).matches() || alias.equals(NAMESPACE) || alias.equals("wc")) {
                    plugin.getLogger().warning("core.aliases : nom de commande invalide « " + name + " ».");
                    continue;
                }
                String[] parts = value.split("\\s+");
                String target = parts[0].toLowerCase(Locale.ROOT);
                Entry entry = subcommands.get(target);
                if (entry == null) {
                    plugin.getLogger().warning("core.aliases." + name + " : sous-commande inconnue « " + target + " ».");
                    continue;
                }
                Alias command = new Alias(alias, target, Arrays.copyOfRange(parts, 1, parts.length), entry);
                if (!map.register(alias, NAMESPACE, command)) {
                    plugin.getLogger().warning("/" + alias + " est déjà utilisée par un autre plugin : raccourci disponible en /"
                            + NAMESPACE + ":" + alias + ".");
                }
                aliases.put(alias, command);
            }
        }
        // Renvoie l'arbre des commandes (autocomplétion, commandes masquées selon les permissions)
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    public void unregisterAliases() {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        for (Alias command : aliases.values()) {
            command.unregister(map);
            for (String key : List.of(command.getName(), NAMESPACE + ":" + command.getName())) {
                if (known.get(key) == command) known.remove(key);
            }
        }
        aliases.clear();
    }

    // -------------------------------------------------------------- utilitaires

    /** "/afk" si un raccourci sans argument fixe mène à la sous-commande, sinon "/wc afk". */
    public String label(String subcommand) {
        for (Alias alias : aliases.values()) {
            if (alias.target.equals(subcommand) && alias.fixedArgs.length == 0) return "/" + alias.getName();
        }
        return "/wc " + subcommand;
    }

    /** Sous-commande WestCore visée par une ligne de commande ("/wc afk", "/afk"...), null sinon. */
    public String target(String commandLine) {
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String[] parts = line.trim().split("\\s+", 3);
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            if (!label.substring(0, colon).equals(NAMESPACE)) return null;
            label = label.substring(colon + 1);
        }
        if (label.equals(NAMESPACE) || label.equals("wc")) {
            return parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null;
        }
        Alias alias = aliases.get(label);
        return alias == null ? null : alias.target;
    }

    /** Raccourcis actifs : nom -> "sous-commande args". */
    public Map<String, String> aliases() {
        Map<String, String> out = new LinkedHashMap<>();
        aliases.forEach((name, a) -> out.put(name, (a.target + " " + String.join(" ", a.fixedArgs)).trim()));
        return out;
    }

    public List<String> subcommandNames() {
        return new ArrayList<>(subcommands.keySet());
    }
}
