package com.westcube.westcore.tab;

import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Colors;
import com.westcube.westcore.core.DataFile;
import com.westcube.westcore.core.Module;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tablist (header / footer), scoreboard latéral avec /sb, préfixes LuckPerms
 * au-dessus des têtes et tri de la tablist par grade (ex-TAB).
 *
 * Chaque joueur a son propre scoreboard Bukkit : il porte son scoreboard latéral
 * et une équipe par joueur connecté (préfixe, suffixe, tri).
 * Placeholders internes : %player%, %ping%, %online%, %staffonline%, puis PlaceholderAPI.
 */
public class TabModule extends Module {

    private static final String OBJECTIVE = "westcube_sb";

    /** Ce qu'affiche l'équipe d'un joueur (identique dans tous les scoreboards). */
    private record Nametag(String team, String prefix, String suffix, int order) {}

    private static final class Board {
        final Scoreboard scoreboard;
        Objective objective;
        String title;
        final List<String> lines = new ArrayList<>();
        String header, footer;

        Board(Scoreboard scoreboard) {
            this.scoreboard = scoreboard;
        }
    }

    private final DataFile file;
    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();
    private final Map<UUID, Nametag> nametags = new HashMap<>();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private boolean papi;

    public TabModule(WestCore plugin) {
        super(plugin, "tab");
        this.file = new DataFile(plugin, "tab.yml");
        permission("scoreboard", "tab.scoreboard.toggle", PermissionDefault.OP, "Afficher ou masquer le scoreboard");
        permission("staff", "tab.staff", PermissionDefault.OP, "Compté dans %staffonline%");
        command("sb", "afficher ou masquer le scoreboard", "scoreboard");
    }

    @Override
    protected void onEnable() {
        papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        hidden.clear();
        for (String uuid : file.load().getStringList("scoreboard-off")) hidden.add(UUID.fromString(uuid));
        importTabPlayerData();

        for (Player p : Bukkit.getOnlinePlayers()) setup(p);

        long refresh = Math.max(5, cfg().getLong("refresh-ticks", 20));
        repeat(this::refreshAll, refresh, refresh);
        long nametagRefresh = Math.max(20, cfg().getLong("nametags.refresh-ticks", 100));
        repeat(this::refreshNametags, nametagRefresh, nametagRefresh);
    }

    @Override
    protected void onDisable() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board board = boards.get(p.getUniqueId());
            if (board != null && p.getScoreboard() == board.scoreboard) p.setScoreboard(main);
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        boards.clear();
        nametags.clear();
        saveHidden();
    }

    /** Reprend la liste des joueurs ayant masqué le scoreboard dans TAB (plugins/TAB/playerdata.yml). */
    private void importTabPlayerData() {
        File tabData = new File(plugin.getDataFolder().getParentFile(), "TAB/playerdata.yml");
        File marker = new File(plugin.getDataFolder(), "data/tab.yml");
        if (marker.exists() || !tabData.exists()) return;
        for (String uuid : YamlConfiguration.loadConfiguration(tabData).getStringList("scoreboard-off")) {
            try {
                hidden.add(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
                // entrée invalide
            }
        }
        saveHidden();
        plugin.getLogger().info("TAB : " + hidden.size() + " joueur(s) avec scoreboard masqué importé(s).");
    }

    private void saveHidden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("scoreboard-off", hidden.stream().map(UUID::toString).sorted().toList());
        file.saveAsync(yaml);
    }

    // ------------------------------------------------------------ connexion

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        setup(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boards.remove(p.getUniqueId());
        Nametag tag = nametags.remove(p.getUniqueId());
        if (tag != null) {
            for (Board board : boards.values()) {
                Team team = board.scoreboard.getTeam(tag.team());
                if (team != null) team.unregister();
            }
        }
    }

    private void setup(Player p) {
        Board board = new Board(Bukkit.getScoreboardManager().getNewScoreboard());
        boards.put(p.getUniqueId(), board);

        // Les équipes des joueurs déjà connectés dans le nouveau scoreboard
        for (Map.Entry<UUID, Nametag> entry : nametags.entrySet()) {
            Player other = Bukkit.getPlayer(entry.getKey());
            if (other != null) applyTeam(board.scoreboard, other, entry.getValue());
        }
        p.setScoreboard(board.scoreboard);

        // L'équipe du nouveau joueur dans tous les scoreboards (le sien compris)
        updateNametag(p);
        refresh(p, board);
    }

    // ---------------------------------------------------------- placeholders

    private String resolve(Player viewer, String text, int online, int staff) {
        if (text == null || text.isEmpty()) return "";
        String out = text
                .replace("%player%", viewer.getName())
                .replace("%ping%", String.valueOf(viewer.getPing()))
                .replace("%online%", String.valueOf(online))
                .replace("%staffonline%", String.valueOf(staff));
        return papi && out.indexOf('%') >= 0 ? PlaceholderAPI.setPlaceholders(viewer, out) : out;
    }

    // --------------------------------------------- tablist + scoreboard latéral

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board board = boards.get(p.getUniqueId());
            if (board != null) refresh(p, board);
        }
    }

    private void refresh(Player p, Board board) {
        // Un autre plugin a remis le scoreboard principal : on reprend la main.
        // S'il a mis son propre scoreboard (mode staff...), on le laisse faire.
        if (p.getScoreboard() != board.scoreboard) {
            if (p.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()) return;
            p.setScoreboard(board.scoreboard);
        }

        int online = 0, staff = 0;
        String staffPermission = perm("staff");
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!p.canSee(other)) continue;
            online++;
            if (other.hasPermission(staffPermission)) staff++;
        }

        if (cfg().getBoolean("header-footer.enabled", true)) {
            String header = join(p, cfg().getStringList("header-footer.header"), online, staff);
            String footer = join(p, cfg().getStringList("header-footer.footer"), online, staff);
            if (!header.equals(board.header) || !footer.equals(board.footer)) {
                board.header = header;
                board.footer = footer;
                p.sendPlayerListHeaderAndFooter(Colors.parse(header), Colors.parse(footer));
            }
        }

        if (!cfg().getBoolean("scoreboard.enabled", true) || hidden.contains(p.getUniqueId())) {
            removeSidebar(board);
            return;
        }
        updateSidebar(p, board, online, staff);
    }

    private String join(Player p, List<String> lines, int online, int staff) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append("<reset><newline>");
            sb.append(Colors.toMiniMessage(resolve(p, lines.get(i), online, staff)));
        }
        return sb.toString();
    }

    private void updateSidebar(Player p, Board board, int online, int staff) {
        List<String> lines = cfg().getStringList("scoreboard.lines");
        String title = resolve(p, cfg().getString("scoreboard.title", ""), online, staff);

        if (board.objective == null || board.lines.size() != lines.size()) {
            removeSidebar(board);
            board.objective = board.scoreboard.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Colors.parse(title));
            board.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            if (cfg().getBoolean("scoreboard.hide-numbers", true)) board.objective.numberFormat(NumberFormat.blank());
            board.title = title;
            for (int i = 0; i < lines.size(); i++) board.lines.add(null);
        }
        if (!title.equals(board.title)) {
            board.title = title;
            board.objective.displayName(Colors.parse(title));
        }

        for (int i = 0; i < lines.size(); i++) {
            String text = resolve(p, lines.get(i), online, staff);
            if (text.equals(board.lines.get(i))) continue;
            board.lines.set(i, text);
            Score score = board.objective.getScore(entry(i));
            score.setScore(lines.size() - i);
            score.customName(Colors.parse(text));
        }
    }

    /** Entrée invisible et unique pour chaque ligne (affichée telle quelle par les vieux clients). */
    private static String entry(int i) {
        String codes = "0123456789abcdef";
        return "§" + codes.charAt(i % 16) + (i >= 16 ? "§" + codes.charAt(i / 16) : "") + "§r";
    }

    private static void removeSidebar(Board board) {
        if (board.objective != null) {
            board.objective.unregister();
            board.objective = null;
        }
        board.lines.clear();
        board.title = null;
    }

    // ---------------------------------------------- préfixes et tri (équipes)

    private void refreshNametags() {
        for (Player p : Bukkit.getOnlinePlayers()) updateNametag(p);
    }

    private void updateNametag(Player p) {
        if (!cfg().getBoolean("nametags.enabled", true)) return;

        String prefix = resolve(p, cfg().getString("nametags.prefix", ""), 0, 0);
        String suffix = resolve(p, cfg().getString("nametags.suffix", ""), 0, 0);
        String group = resolve(p, cfg().getString("nametags.group-placeholder", ""), 0, 0).toLowerCase(Locale.ROOT);

        List<String> sorting = cfg().getStringList("nametags.sorting");
        int index = sorting.indexOf(group);
        if (index < 0) index = sorting.size();

        // Nom d'équipe = rang du grade + pseudo : le client trie la tablist par nom d'équipe
        String team = String.format("%02d", index) + p.getName();
        if (team.length() > 16) team = team.substring(0, 16);
        Nametag tag = new Nametag(team, prefix, suffix, sorting.size() - index);

        Nametag old = nametags.put(p.getUniqueId(), tag);
        if (tag.equals(old)) return;

        for (Board board : boards.values()) {
            if (old != null && !old.team().equals(tag.team())) {
                Team previous = board.scoreboard.getTeam(old.team());
                if (previous != null) previous.unregister();
            }
            applyTeam(board.scoreboard, p, tag);
        }
        p.setPlayerListOrder(tag.order());
    }

    private void applyTeam(Scoreboard scoreboard, Player p, Nametag tag) {
        Team team = scoreboard.getTeam(tag.team());
        if (team == null) team = scoreboard.registerNewTeam(tag.team());
        team.prefix(Colors.parse(tag.prefix()));
        team.suffix(Colors.parse(tag.suffix()));
        // Le pseudo prend la dernière couleur du préfixe
        team.color(Objects.requireNonNullElse(Colors.lastColor(tag.prefix()), NamedTextColor.WHITE));
        team.setOption(Team.Option.COLLISION_RULE,
                cfg().getBoolean("nametags.collision", true) ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        if (!team.hasEntry(p.getName())) team.addEntry(p.getName());
    }

    // ------------------------------------------------------------------ /sb

    @Override
    public boolean onCommand(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(global("players-only"));
            return true;
        }
        Board board = boards.get(p.getUniqueId());
        if (hidden.remove(p.getUniqueId())) {
            p.sendMessage(msg("scoreboard-on"));
            if (board != null) refresh(p, board);
        } else {
            hidden.add(p.getUniqueId());
            p.sendMessage(msg("scoreboard-off"));
            if (board != null) removeSidebar(board);
        }
        if (cfg().getBoolean("scoreboard.remember-toggle", true)) saveHidden();
        return true;
    }
}
