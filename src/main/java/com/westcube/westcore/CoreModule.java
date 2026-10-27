package com.westcube.westcore;

import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Text;
import com.westcube.westcore.migrate.SkriptMigrator;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Administration de WestCore : /westcore reload | modules | perms | migrate-skript.
 * Section "core" de config.yml : permissions et raccourcis de commandes.
 */
final class CoreModule extends Module {

    CoreModule(WestCore plugin) {
        super(plugin, "core");
        permission("admin", "westcore.admin", PermissionDefault.OP, "Administration de WestCore");
        command("reload", "recharger la config et redémarrer les modules", "admin");
        command("modules", "état des modules", "admin");
        command("perms", "permissions et raccourcis effectifs", "admin");
        command("migrate-skript", "importer les variables Skript", "admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Text.mm("<green>WestCore rechargé."));
            }
            case "modules" -> {
                sender.sendMessage(Text.mm("<gray>Modules WestCore :"));
                for (Module module : plugin.modules()) {
                    if (module == this) continue;
                    sender.sendMessage(Text.mm(" <dark_gray>•</dark_gray> <white>" + module.id() + "</white> : "
                            + (module.isEnabled() ? "<green>actif" : "<red>inactif")));
                }
            }
            case "perms" -> perms(sender);
            case "migrate-skript" -> {
                File csv = args.length > 0
                        ? new File(args[0])
                        : new File(plugin.getDataFolder().getParentFile(), "Skript/variables.csv");
                for (String line : new SkriptMigrator(plugin).migrate(csv)) {
                    sender.sendMessage(Text.mm("<gray>" + Text.escape(line)));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void perms(CommandSender sender) {
        sender.sendMessage(Text.mm("<gray>Permissions (<white>config.yml</white> → <white><module>.permissions</white>) :"));
        for (Module module : plugin.modules()) {
            if (module.permissionSpecs().isEmpty()) continue;
            sender.sendMessage(Text.mm(" <#ffd027>" + module.id() + (module.isEnabled() ? "" : " <red>(inactif)")));
            for (Module.PermissionSpec spec : module.permissionSpecs().values()) {
                String name = module.perm(spec.key());
                String changed = name.equals(spec.defaultName()) ? "" : " <dark_gray>(défaut : " + spec.defaultName() + ")";
                sender.sendMessage(Text.mm("   <dark_gray>•</dark_gray> <gray>" + spec.key() + "</gray> : <white>"
                        + Text.escape(name) + "</white>" + changed));
            }
        }
        Map<String, String> aliases = plugin.commands().aliases();
        sender.sendMessage(Text.mm("<gray>Raccourcis (<white>core.aliases</white>) :"));
        if (aliases.isEmpty()) sender.sendMessage(Text.mm("   <dark_gray>• <gray>aucun"));
        aliases.forEach((alias, target) -> sender.sendMessage(Text.mm("   <dark_gray>•</dark_gray> <white>/"
                + Text.escape(alias) + "</white> <gray>→ /wc " + Text.escape(target))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String name, String[] args) {
        return List.of();
    }
}
