package com.westcube.westcore.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.westcube.westcore.WestCore;
import com.westcube.westcore.core.Module;
import com.westcube.westcore.core.Players;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API HTTP lue par le plugin WordPress WestAPI (en-tête X-API-Key).
 * Les réponses globales sont préparées sur le thread principal toutes les "refresh-seconds" :
 * les threads HTTP ne font que renvoyer du JSON déjà calculé. Les valeurs d'un joueur sont
 * calculées à la demande sur le thread principal, puis gardées "player-cache-seconds".
 */
public class ApiModule extends Module {

    private static final String DEFAULT_KEY = "changez-moi";
    private static final Pattern PLAYER_ROUTE = Pattern.compile("/api/joueur/([A-Za-z0-9_.]{1,16})/codes");
    private static final int PLAYER_CACHE_MAX = 500;

    private final Map<String, Supplier<String>> routes = new ConcurrentHashMap<>();
    private final Map<String, Cached> playerCache = new ConcurrentHashMap<>();
    private volatile String topVotersJson;
    private volatile String codesJson;
    private ShortCodes codes;
    private String catalogJson;
    private long playerCacheMillis;
    private HttpServer server;
    private ExecutorService executor;
    private byte[] apiKey;

    private record Cached(long at, String json) {}

    /** Réponse d'erreur levée pendant le traitement d'une route. */
    private static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    public ApiModule(WestCore plugin) {
        super(plugin, "api");
    }

    @Override
    protected void onEnable() {
        importLegacyConfig();

        String key = cfg().getString("api-key", DEFAULT_KEY);
        if (DEFAULT_KEY.equals(key) || key.isBlank()) {
            plugin.getLogger().warning("api.api-key n'est pas configurée : l'API ne démarre pas.");
            return;
        }
        apiKey = key.getBytes(StandardCharsets.UTF_8);

        routes.clear();
        playerCache.clear();
        codes = null;
        boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        long refreshSeconds = Math.max(5, cfg().getLong("refresh-seconds", 60));
        playerCacheMillis = Math.max(5, cfg().getLong("player-cache-seconds", 30)) * 1000L;

        if (cfg().getBoolean("routes.top-voters", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("VotingPlugin") && papi) {
                routes.put("/api/top-voters", () -> topVotersJson);
            } else {
                plugin.getLogger().warning("VotingPlugin ou PlaceholderAPI absent : route /api/top-voters désactivée.");
            }
        }
        if (cfg().getBoolean("routes.codes", true)) {
            if (papi) {
                codes = ShortCodes.load(plugin);
                catalogJson = codes.catalogJson(refreshSeconds, playerCacheMillis / 1000);
                routes.put("/api/codes", () -> catalogJson);
                routes.put("/api/codes/valeurs", () -> codesJson);
            } else {
                plugin.getLogger().warning("PlaceholderAPI absent : routes /api/codes désactivées.");
            }
        }

        repeat(this::refresh, 20L, refreshSeconds * 20L);

        String bind = cfg().getString("bind", "0.0.0.0");
        int port = cfg().getInt("port", 41240);
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "WestCore-API");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("API démarrée sur " + bind + ":" + port + " (" + routes.keySet()
                    + (codes != null ? " + /api/joueur/<pseudo>/codes" : "") + ")");
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de démarrer l'API HTTP : " + e.getMessage());
        }
    }

    @Override
    protected void onDisable() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Reprend le port et la clé de l'ancien plugin WestAPI au premier démarrage. */
    private void importLegacyConfig() {
        if (!DEFAULT_KEY.equals(cfg().getString("api-key", DEFAULT_KEY))) return;
        File legacy = new File(plugin.getDataFolder().getParentFile(), "WestAPI/config.yml");
        if (!legacy.exists()) return;

        YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
        String key = old.getString("api-key");
        if (key == null || key.isBlank()) return;

        plugin.getConfig().set("api.api-key", key);
        plugin.getConfig().set("api.port", old.getInt("port", 41240));
        plugin.saveConfig();
        plugin.getLogger().info("Clé et port importés depuis plugins/WestAPI/config.yml.");
    }

    // ------------------------------------------------------------ données

    private void refresh() {
        if (routes.containsKey("/api/top-voters")) {
            topVotersJson = buildTopVoters();
        }
        if (codes != null) {
            codesJson = codes.resolve(null, ShortCodes.Scope.GLOBAL);
        }
    }

    private String buildTopVoters() {
        StringBuilder json = new StringBuilder("{\"topVoters\":[");
        for (int i = 1; i <= 3; i++) {
            String name = PlaceholderAPI.setPlaceholders(null, "%VotingPlugin_Top_Month_" + i + "%");
            String votes = PlaceholderAPI.setPlaceholders(null, "%VotingPlugin_Top_MonthVotes_" + i + "%");
            if (i > 1) json.append(',');
            json.append("{\"rank\":").append(i)
                    .append(",\"name\":").append(Json.quote(name))
                    .append(",\"votes\":").append(Json.quote(votes))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    /** Appelé depuis un thread HTTP : le calcul est confié au thread principal. */
    private String playerCodes(String name) {
        String cacheKey = name.toLowerCase(Locale.ROOT);
        Cached cached = playerCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < playerCacheMillis) return cached.json();

        String json;
        try {
            json = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                OfflinePlayer player = Players.find(name);
                return player == null ? null : codes.resolve(player, ShortCodes.Scope.JOUEUR);
            }).get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiError(503, "Server busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(503, "Server busy");
        } catch (Exception e) {
            throw new ApiError(500, "Internal error");
        }
        if (json == null) throw new ApiError(404, "Unknown player");

        if (playerCache.size() >= PLAYER_CACHE_MAX) playerCache.clear();
        playerCache.put(cacheKey, new Cached(now, json));
        return json;
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, Json.error("Method not allowed"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Supplier<String> route = routes.get(path);
            if (route == null && codes != null && codes.has(ShortCodes.Scope.JOUEUR)) {
                Matcher m = PLAYER_ROUTE.matcher(path);
                if (m.matches()) {
                    String name = m.group(1);
                    route = () -> playerCodes(name);
                }
            }
            if (route == null) {
                send(exchange, 404, Json.error("Route not found"));
                return;
            }
            if (!authorized(exchange)) {
                send(exchange, 401, Json.error("Unauthorized"));
                return;
            }
            String body;
            try {
                body = route.get();
            } catch (ApiError e) {
                send(exchange, e.status, Json.error(e.getMessage()));
                return;
            }
            if (body == null) {
                send(exchange, 503, Json.error("Data not ready"));
                return;
            }
            send(exchange, 200, body);
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur API sur " + exchange.getRequestURI() + " : " + e.getMessage());
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-API-Key");
        return header != null && apiKey != null
                && MessageDigest.isEqual(apiKey, header.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
