package com.sourov.telegrambridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class TelegramMinecraftBridge
        extends JavaPlugin
        implements Listener {

    private HttpClient httpClient;

    private String botToken;
    private long adminId;

    private int pollIntervalSeconds;
    private boolean forwardMinecraftChat;

    private volatile boolean running = false;

    private long updateOffset = 0L;

    private Thread pollingThread;

    // =========================================================
    // ENABLE
    // =========================================================

    @Override
    public void onEnable() {

        saveDefaultConfig();

        botToken = getConfig()
                .getString("bot-token", "")
                .trim();

        adminId = getConfig()
                .getLong("admin-id", 0L);

        pollIntervalSeconds = Math.max(
                1,
                getConfig().getInt(
                        "poll-interval-seconds",
                        2
                )
        );

        forwardMinecraftChat = getConfig()
                .getBoolean(
                        "forward-minecraft-chat",
                        true
                );

        // -----------------------------------------------------
        // Check configuration
        // -----------------------------------------------------

        if (botToken.isEmpty()
                || botToken.equals(
                "PASTE_NEW_BOTFATHER_TOKEN_HERE"
        )) {

            getLogger().severe(
                    "Telegram bot token is not configured!"
            );

            getLogger().severe(
                    "Put your NEW BotFather token in:"
            );

            getLogger().severe(
                    "plugins/TelegramMinecraftBridge/config.yml"
            );

            Bukkit.getPluginManager()
                    .disablePlugin(this);

            return;
        }

        if (adminId <= 0) {

            getLogger().severe(
                    "Invalid admin-id in config.yml!"
            );

            Bukkit.getPluginManager()
                    .disablePlugin(this);

            return;
        }

        // -----------------------------------------------------
        // HTTP client
        // -----------------------------------------------------

        httpClient = HttpClient.newBuilder()
                .connectTimeout(
                        Duration.ofSeconds(10)
                )
                .build();

        // -----------------------------------------------------
        // Minecraft events
        // -----------------------------------------------------

        Bukkit.getPluginManager()
                .registerEvents(this, this);

        // -----------------------------------------------------
        // Telegram command menu
        // -----------------------------------------------------

        registerTelegramCommands();

        // -----------------------------------------------------
        // Start polling
        // -----------------------------------------------------

        running = true;

        pollingThread = new Thread(
                this::pollTelegram,
                "TelegramMinecraftBridge-Poll"
        );

        pollingThread.setDaemon(true);
        pollingThread.start();

        getLogger().info(
                "===================================="
        );

        getLogger().info(
                "TelegramMinecraftBridge ENABLED"
        );

        getLogger().info(
                "Telegram Admin ID: " + adminId
        );

        getLogger().info(
                "RCON is NOT required."
        );

        getLogger().info(
                "===================================="
        );
    }

    // =========================================================
    // DISABLE
    // =========================================================

    @Override
    public void onDisable() {

        running = false;

        if (pollingThread != null) {
            pollingThread.interrupt();
        }

        getLogger().info(
                "TelegramMinecraftBridge disabled."
        );
    }

    // =========================================================
    // MINECRAFT CHAT -> TELEGRAM
    // =========================================================

    @EventHandler
    public void onMinecraftChat(
            AsyncPlayerChatEvent event
    ) {

        if (!forwardMinecraftChat) {
            return;
        }

        String playerName =
                event.getPlayer().getName();

        String message =
                event.getMessage();

        String telegramMessage =
                "💬 Minecraft Chat\n\n"
                        + "👤 "
                        + playerName
                        + "\n"
                        + "💬 "
                        + message;

        sendTelegram(telegramMessage);
    }

    // =========================================================
    // TELEGRAM COMMAND MENU
    // =========================================================

    private void registerTelegramCommands() {

        JsonArray commands = new JsonArray();

        addCommand(
                commands,
                "start",
                "Start bridge"
        );

        addCommand(
                commands,
                "help",
                "Show commands"
        );

        addCommand(
                commands,
                "status",
                "Server status"
        );

        addCommand(
                commands,
                "players",
                "Online players"
        );

        addCommand(
                commands,
                "say",
                "Send Minecraft chat"
        );

        addCommand(
                commands,
                "kick",
                "Kick player"
        );

        addCommand(
                commands,
                "ban",
                "Ban player"
        );

        addCommand(
                commands,
                "whitelist",
                "Whitelist player"
        );

        addCommand(
                commands,
                "tp",
                "Teleport player"
        );

        addCommand(
                commands,
                "weather",
                "Change weather"
        );

        addCommand(
                commands,
                "time",
                "Change time"
        );

        addCommand(
                commands,
                "broadcast",
                "Broadcast message"
        );

        addCommand(
                commands,
                "tps",
                "Show server TPS"
        );

        JsonObject body =
                new JsonObject();

        body.add(
                "commands",
                commands
        );

        postTelegram(
                "setMyCommands",
                body.toString()
        );
    }

    private void addCommand(
            JsonArray commands,
            String command,
            String description
    ) {

        JsonObject object =
                new JsonObject();

        object.addProperty(
                "command",
                command
        );

        object.addProperty(
                "description",
                description
        );

        commands.add(object);
    }

    // =========================================================
    // TELEGRAM POLLING
    // =========================================================

    private void pollTelegram() {

        while (running) {

            try {

                String url =
                        telegramApi("getUpdates")
                                + "&timeout=20"
                                + "&offset="
                                + updateOffset;

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(
                                        Duration.ofSeconds(30)
                                )
                                .GET()
                                .build();

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers
                                        .ofString()
                        );

                if (response.statusCode() != 200) {

                    getLogger().warning(
                            "Telegram HTTP error: "
                                    + response.statusCode()
                    );

                    sleep(3000);

                    continue;
                }

                JsonObject root =
                        JsonParser
                                .parseString(
                                        response.body()
                                )
                                .getAsJsonObject();

                if (!root.has("ok")
                        || !root.get("ok")
                        .getAsBoolean()) {

                    getLogger().warning(
                            "Telegram API returned an error."
                    );

                    sleep(3000);

                    continue;
                }

                JsonArray updates =
                        root.getAsJsonArray(
                                "result"
                        );

                for (JsonElement element :
                        updates) {

                    JsonObject update =
                            element.getAsJsonObject();

                    if (update.has(
                            "update_id"
                    )) {

                        updateOffset =
                                update.get(
                                        "update_id"
                                ).getAsLong() + 1;
                    }

                    handleTelegramUpdate(
                            update
                    );
                }

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();

                return;

            } catch (Exception e) {

                getLogger().warning(
                        "Telegram polling error: "
                                + e.getMessage()
                );

                sleep(3000);
            }
        }
    }

    // =========================================================
    // TELEGRAM UPDATE
    // =========================================================

    private void handleTelegramUpdate(
            JsonObject update
    ) {

        if (!update.has("message")) {
            return;
        }

        JsonObject message =
                update.getAsJsonObject(
                        "message"
                );

        if (!message.has("chat")
                || !message.has("text")) {

            return;
        }

        JsonObject chat =
                message.getAsJsonObject(
                        "chat"
                );

        long chatId =
                chat.get("id").getAsLong();

        // -----------------------------------------------------
        // Security: only admin
        // -----------------------------------------------------

        if (chatId != adminId) {

            sendTelegramTo(
                    chatId,
                    "❌ You are not authorized "
                            + "to control this server."
            );

            return;
        }

        String text =
                message.get("text")
                        .getAsString()
                        .trim();

        if (text.isEmpty()) {
            return;
        }

        // Minecraft API must run on main thread
        Bukkit.getScheduler().runTask(
                this,
                () -> handleCommand(
                        chatId,
                        text
                )
        );
    }

    // =========================================================
    // COMMAND HANDLER
    // =========================================================

    private void handleCommand(
            long chatId,
            String text
    ) {

        String[] parts =
                text.split("\\s+", 2);

        String command =
                parts[0].toLowerCase();

        if (command.startsWith("/")) {

            command =
                    command.substring(1);
        }

        String args =
                parts.length > 1
                        ? parts[1].trim()
                        : "";

        switch (command) {

            // -------------------------------------------------
            // START
            // -------------------------------------------------

            case "start":

                sendTelegramTo(
                        chatId,
                        "🎮 Telegram Minecraft Bridge\n\n"
                                + "✅ Connected\n"
                                + "🟢 Server bridge is running.\n\n"
                                + "Use /help to see commands."
                );

                break;

            // -------------------------------------------------
            // HELP
            // -------------------------------------------------

            case "help":

                sendTelegramTo(
                        chatId,
                        getHelpText()
                );

                break;

            // -------------------------------------------------
            // STATUS
            // -------------------------------------------------

            case "status":

                sendServerStatus(
                        chatId
                );

                break;

            // -------------------------------------------------
            // PLAYERS
            // -------------------------------------------------

            case "players":

                sendPlayers(
                        chatId
                );

                break;

            // -------------------------------------------------
            // SAY
            // -------------------------------------------------

            case "say":

                if (args.isEmpty()) {

                    sendTelegramTo(
                            chatId,
                            "❌ Usage:\n"
                                    + "/say <message>"
                    );

                    break;
                }

                Bukkit.broadcastMessage(
                        "§b[Telegram] §f"
                                + args
                );

                sendTelegramTo(
                        chatId,
                        "✅ Message sent to Minecraft."
                );

                break;

            // -------------------------------------------------
            // KICK
            // -------------------------------------------------

            case "kick":

                kickPlayer(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // BAN
            // -------------------------------------------------

            case "ban":

                banPlayer(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // WHITELIST
            // -------------------------------------------------

            case "whitelist":

                whitelistPlayer(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // TP
            // -------------------------------------------------

            case "tp":

                teleportPlayer(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // WEATHER
            // -------------------------------------------------

            case "weather":

                changeWeather(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // TIME
            // -------------------------------------------------

            case "time":

                changeTime(
                        chatId,
                        args
                );

                break;

            // -------------------------------------------------
            // BROADCAST
            // -------------------------------------------------

            case "broadcast":

                if (args.isEmpty()) {

                    sendTelegramTo(
                            chatId,
                            "❌ Usage:\n"
                                    + "/broadcast <message>"
                    );

                    break;
                }

                Bukkit.broadcastMessage(
                        "§6[Broadcast] §f"
                                + args
                );

                sendTelegramTo(
                        chatId,
                        "📢 Broadcast sent."
                );

                break;

            // -------------------------------------------------
            // TPS
            // -------------------------------------------------

            case "tps":

                sendTPS(
                        chatId
                );

                break;

            // -------------------------------------------------
            // UNKNOWN
            // -------------------------------------------------

            default:

                sendTelegramTo(
                        chatId,
                        "❓ Unknown command.\n\n"
                                + "Use /help."
                );

                break;
        }
    }

    // =========================================================
    // HELP TEXT
    // =========================================================

    private String getHelpText() {

        return
                "🎮 Minecraft Server Control\n\n"

                        + "📊 Information\n"
                        + "/status\n"
                        + "/players\n"
                        + "/tps\n\n"

                        + "💬 Chat\n"
                        + "/say <message>\n"
                        + "/broadcast <message>\n\n"

                        + "👤 Player Control\n"
                        + "/kick <player>\n"
                        + "/ban <player>\n"
                        + "/whitelist <player>\n"
                        + "/tp <player>\n\n"

                        + "🌦 World Control\n"
                        + "/weather clear\n"
                        + "/weather rain\n"
                        + "/time day\n"
                        + "/time night";
    }

    // =========================================================
    // STATUS
    // =========================================================

    private void sendServerStatus(
            long chatId
    ) {

        int online =
                Bukkit.getOnlinePlayers()
                        .size();

        int max =
                Bukkit.getMaxPlayers();

        int worlds =
                Bukkit.getWorlds()
                        .size();

        String version =
                Bukkit.getMinecraftVersion();

        String message =
                "🟢 Server Status\n\n"
                        + "👥 Players: "
                        + online
                        + "/"
                        + max
                        + "\n"
                        + "🎮 Version: "
                        + version
                        + "\n"
                        + "🌍 Worlds: "
                        + worlds;

        sendTelegramTo(
                chatId,
                message
        );
    }

    // =========================================================
    // PLAYERS
    // =========================================================

    private void sendPlayers(
            long chatId
    ) {

        List<Player> players =
                new ArrayList<>(
                        Bukkit.getOnlinePlayers()
                );

        if (players.isEmpty()) {

            sendTelegramTo(
                    chatId,
                    "👥 Online Players\n\n"
                            + "No players are online."
            );

            return;
        }

        StringBuilder message =
                new StringBuilder();

        message.append(
                "👥 Online Players\n\n"
        );

        for (Player player : players) {

            message.append("• ")
                    .append(
                            player.getName()
                    )
                    .append("\n");
        }

        sendTelegramTo(
                chatId,
                message.toString()
        );
    }

    // =========================================================
    // KICK
    // =========================================================

    private void kickPlayer(
            long chatId,
            String name
    ) {

        if (name.isEmpty()) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/kick <player>"
            );

            return;
        }

        Player player =
                Bukkit.getPlayerExact(
                        name
                );

        if (player == null) {

            sendTelegramTo(
                    chatId,
                    "❌ Player is not online:\n"
                            + name
            );

            return;
        }

        player.kickPlayer(
                "Kicked by Telegram admin."
        );

        sendTelegramTo(
                chatId,
                "✅ Player kicked:\n"
                        + player.getName()
        );
    }

    // =========================================================
    // BAN
    // =========================================================

    private void banPlayer(
            long chatId,
            String name
    ) {

        if (name.isEmpty()) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/ban <player>"
            );

            return;
        }

        Player onlinePlayer =
                Bukkit.getPlayerExact(
                        name
                );

        String targetName =
                onlinePlayer != null
                        ? onlinePlayer.getName()
                        : name;

        Bukkit.getBanList(
                        BanList.Type.NAME
                )
                .addBan(
                        targetName,
                        "Banned by Telegram admin.",
                        null,
                        "Telegram"
                );

        if (onlinePlayer != null) {

            onlinePlayer.kickPlayer(
                    "Banned by Telegram admin."
            );
        }

        sendTelegramTo(
                chatId,
                "🔨 Player banned:\n"
                        + targetName
        );
    }

    // =========================================================
    // WHITELIST
    // =========================================================

    private void whitelistPlayer(
            long chatId,
            String name
    ) {

        if (name.isEmpty()) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/whitelist <player>"
            );

            return;
        }

        OfflinePlayer player =
                Bukkit.getOfflinePlayer(
                        name
                );

        player.setWhitelisted(
                true
        );

        sendTelegramTo(
                chatId,
                "✅ Player added to whitelist:\n"
                        + name
        );
    }

    // =========================================================
    // TELEPORT
    // =========================================================

    private void teleportPlayer(
            long chatId,
            String name
    ) {

        if (name.isEmpty()) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/tp <player>"
            );

            return;
        }

        Player player =
                Bukkit.getPlayerExact(
                        name
                );

        if (player == null) {

            sendTelegramTo(
                    chatId,
                    "❌ Player is not online:\n"
                            + name
            );

            return;
        }

        World world =
                player.getWorld();

        player.teleport(
                world.getSpawnLocation()
        );

        sendTelegramTo(
                chatId,
                "📍 Teleported:\n"
                        + player.getName()
                        + "\n\n"
                        + "Destination: World Spawn"
        );
    }

    // =========================================================
    // WEATHER
    // =========================================================

    private void changeWeather(
            long chatId,
            String weather
    ) {

        if (!weather.equalsIgnoreCase("clear")
                && !weather.equalsIgnoreCase("rain")) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/weather clear\n"
                            + "/weather rain"
            );

            return;
        }

        boolean rain =
                weather.equalsIgnoreCase(
                        "rain"
                );

        for (World world :
                Bukkit.getWorlds()) {

            world.setStorm(rain);

            world.setThundering(
                    false
            );
        }

        sendTelegramTo(
                chatId,
                "🌦 Weather changed to:\n"
                        + weather.toLowerCase()
        );
    }

    // =========================================================
    // TIME
    // =========================================================

    private void changeTime(
            long chatId,
            String time
    ) {

        if (!time.equalsIgnoreCase("day")
                && !time.equalsIgnoreCase("night")) {

            sendTelegramTo(
                    chatId,
                    "❌ Usage:\n"
                            + "/time day\n"
                            + "/time night"
            );

            return;
        }

        long ticks =
                time.equalsIgnoreCase("day")
                        ? 1000L
                        : 13000L;

        for (World world :
                Bukkit.getWorlds()) {

            world.setTime(
                    ticks
            );
        }

        sendTelegramTo(
                chatId,
                "🕒 Time changed to:\n"
                        + time.toLowerCase()
        );
    }

    // =========================================================
    // TPS
    // =========================================================

    private void sendTPS(
            long chatId
    ) {

        double[] tps =
                Bukkit.getTPS();

        double current =
                Math.min(
                        tps[0],
                        20.0
                );

        double oneMinute =
                Math.min(
                        tps[1],
                        20.0
                );

        double fiveMinute =
                Math.min(
                        tps[2],
                        20.0
                );

        String message =
                String.format(
                        "📊 Server TPS\n\n"
                                + "⚡ Current: %.2f\n"
                                + "🕐 1 Minute: %.2f\n"
                                + "🕔 5 Minutes: %.2f",
                        current,
                        oneMinute,
                        fiveMinute
                );

        sendTelegramTo(
                chatId,
                message
        );
    }

    // =========================================================
    // TELEGRAM API URL
    // =========================================================

    private String telegramApi(
            String method
    ) {

        return "https://api.telegram.org/bot"
                + botToken
                + "/"
                + method
                + "?";
    }

    // =========================================================
    // SEND TELEGRAM MESSAGE
    // =========================================================

    private void sendTelegram(
            String message
    ) {

        sendTelegramTo(
                adminId,
                message
        );
    }

    private void sendTelegramTo(
            long chatId,
            String message
    ) {

        try {

            String encodedMessage =
                    URLEncoder.encode(
                            message,
                            StandardCharsets.UTF_8
                    );

            String url =
                    telegramApi(
                            "sendMessage"
                    )
                    + "chat_id="
                    + chatId
                    + "&text="
                    + encodedMessage;

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(url)
                            )
                            .timeout(
                                    Duration.ofSeconds(15)
                            )
                            .GET()
                            .build();

            httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers
                            .ofString()
            ).exceptionally(error -> {

                getLogger().warning(
                        "Telegram send error: "
                                + error.getMessage()
                );

                return null;
            });

        } catch (Exception e) {

            getLogger().warning(
                    "Could not send Telegram message: "
                            + e.getMessage()
            );
        }
    }

    // =========================================================
    // TELEGRAM POST API
    // =========================================================

    private void postTelegram(
            String method,
            String json
    ) {

        try {

            String url =
                    telegramApi(
                            method
                    ).replace(
                            "?",
                            ""
                    );

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(url)
                            )
                            .timeout(
                                    Duration.ofSeconds(15)
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(json)
                            )
                            .build();

            httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers
                            .ofString()
            ).exceptionally(error -> {

                getLogger().warning(
                        "Telegram API error: "
                                + error.getMessage()
                );

                return null;
            });

        } catch (Exception e) {

            getLogger().warning(
                    "Could not call Telegram API: "
                            + e.getMessage()
            );
        }
    }

    // =========================================================
    // SLEEP
    // =========================================================

    private void sleep(
            long milliseconds
    ) {

        try {

            Thread.sleep(
                    milliseconds
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();
        }
    }
}
