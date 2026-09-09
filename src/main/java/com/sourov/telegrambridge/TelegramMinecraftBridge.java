package com.sourov.telegrambridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import java.util.Date;
import java.util.stream.Collectors;

public class TelegramMinecraftBridge extends JavaPlugin implements Listener {

    private String botToken;
    private long adminId;
    private int updateOffset = 0;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        botToken = getConfig().getString("bot-token", "");
        adminId = getConfig().getLong("admin-id", 0);

        if (botToken.isBlank() || botToken.contains("PASTE_NEW")) {
            getLogger().warning("Telegram bot token is not configured!");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("TelegramMinecraftBridge enabled!");

        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::pollTelegram,
                20L,
                getConfig().getLong("poll-interval-seconds", 2) * 20L
        );
    }

    @Override
    public void onDisable() {
        getLogger().info("TelegramMinecraftBridge disabled.");
    }

    // Minecraft chat -> Telegram
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {

        if (!getConfig().getBoolean("forward-minecraft-chat", true)) {
            return;
        }

        String message = "💬 Minecraft\n"
                + event.getPlayer().getName()
                + ": "
                + event.getMessage();

        sendTelegram(message);
    }

    // Telegram update polling
    private void pollTelegram() {

        try {
            String url = "https://api.telegram.org/bot"
                    + botToken
                    + "/getUpdates?timeout=0&offset="
                    + updateOffset;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            JsonObject root =
                    JsonParser.parseString(response.body())
                            .getAsJsonObject();

            if (!root.get("ok").getAsBoolean()) {
                return;
            }

            JsonArray updates = root.getAsJsonArray("result");

            for (var element : updates) {

                JsonObject update =
                        element.getAsJsonObject();

                updateOffset =
                        update.get("update_id").getAsInt() + 1;

                if (!update.has("message")) {
                    continue;
                }

                JsonObject message =
                        update.getAsJsonObject("message");

                if (!message.has("from")
                        || !message.has("chat")) {
                    continue;
                }

                long userId =
                        message.getAsJsonObject("from")
                                .get("id")
                                .getAsLong();

                // Only admin can control the server
                if (userId != adminId) {
                    continue;
                }

                if (!message.has("text")) {
                    continue;
                }

                long chatId =
                        message.getAsJsonObject("chat")
                                .get("id")
                                .getAsLong();

                String text =
                        message.get("text")
                                .getAsString();

                // Minecraft API must run on main thread
                Bukkit.getScheduler().runTask(
                        this,
                        () -> handleCommand(chatId, text)
                );
            }

        } catch (Exception e) {
            getLogger().warning(
                    "Telegram connection error: "
                            + e.getMessage()
            );
        }
    }

    // Telegram commands
    private void handleCommand(long chatId, String text) {

        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {

            case "/start":
                sendTelegramTo(
                        chatId,
                        "🤖 Telegram Minecraft Bridge\n\n"
                                + "Available Commands:\n\n"
                                + "/status\n"
                                + "/players\n"
                                + "/say <message>\n"
                                + "/kick <player>\n"
                                + "/ban <player>\n"
                                + "/whitelist <player>\n"
                                + "/tp <player>\n"
                                + "/weather <clear/rain>\n"
                                + "/time <day/night>\n"
                                + "/broadcast <message>\n"
                                + "/tps"
                );
                break;

            case "/help":
                sendTelegramTo(
                        chatId,
                        "📖 Commands:\n\n"
                                + "/status\n"
                                + "/players\n"
                                + "/say <message>\n"
                                + "/kick <player>\n"
                                + "/ban <player>\n"
                                + "/whitelist <player>\n"
                                + "/tp <player>\n"
                                + "/weather <clear/rain>\n"
                                + "/time <day/night>\n"
                                + "/broadcast <message>\n"
                                + "/tps"
                );
                break;

            case "/status":
                int online =
                        Bukkit.getOnlinePlayers().size();

                sendTelegramTo(
                        chatId,
                        "🟢 Server Online\n\n"
                                + "👥 Players: "
                                + online
                );
                break;

            case "/players":

                String players =
                        Bukkit.getOnlinePlayers()
                                .stream()
                                .map(Player::getName)
                                .collect(Collectors.joining(", "));

                if (players.isBlank()) {
                    players = "No players online.";
                }

                sendTelegramTo(
                        chatId,
                        "👥 Online Players\n\n"
                                + players
                );
                break;

            case "/say":

                if (!getConfig().getBoolean("enable-say", true)) {
                    sendTelegramTo(
                            chatId,
                            "❌ /say is disabled."
                    );
                    break;
                }

                if (parts.length < 2
                        || parts[1].trim().isBlank()) {

                    sendTelegramTo(
                            chatId,
                            "Usage: /say <message>"
                    );
                    break;
                }

                String sayMessage =
                        parts[1].trim();

                Bukkit.broadcastMessage(
                        ChatColor.AQUA
                                + "[Telegram] "
                                + ChatColor.WHITE
                                + sayMessage
                );

                sendTelegramTo(
                        chatId,
                        "✅ Sent to Minecraft:\n"
                                + sayMessage
                );
                break;

            // /kick <player>
            case "/kick":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /kick <player>"
                    );
                    break;
                }

                String kickName =
                        parts[1].trim();

                Player kickPlayer =
                        Bukkit.getPlayerExact(kickName);

                if (kickPlayer == null) {
                    sendTelegramTo(
                            chatId,
                            "❌ Player is not online: "
                                    + kickName
                    );
                    break;
                }

                kickPlayer.kickPlayer(
                        "Kicked by server admin."
                );

                sendTelegramTo(
                        chatId,
                        "✅ Kicked: " + kickName
                );
                break;

            // /ban <player>
            case "/ban":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /ban <player>"
                    );
                    break;
                }

                String banName =
                        parts[1].trim();

                Bukkit.getBanList(BanList.Type.NAME)
                        .addBan(
                                banName,
                                "Banned by server admin.",
                                (Date) null,
                                "Telegram"
                        );

                Player bannedPlayer =
                        Bukkit.getPlayerExact(banName);

                if (bannedPlayer != null) {
                    bannedPlayer.kickPlayer(
                            "You are banned from this server."
                    );
                }

                sendTelegramTo(
                        chatId,
                        "🔨 Banned: " + banName
                );
                break;

            // /whitelist <player>
            case "/whitelist":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /whitelist <player>"
                    );
                    break;
                }

                String whitelistName =
                        parts[1].trim();

                OfflinePlayer offlinePlayer =
                        Bukkit.getOfflinePlayer(
                                whitelistName
                        );

                offlinePlayer.setWhitelisted(true);

                sendTelegramTo(
                        chatId,
                        "✅ Added to whitelist: "
                                + whitelistName
                );
                break;

            // /tp <player>
            // Teleports player to world spawn
            case "/tp":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /tp <player>"
                    );
                    break;
                }

                String tpName =
                        parts[1].trim();

                Player tpPlayer =
                        Bukkit.getPlayerExact(tpName);

                if (tpPlayer == null) {
                    sendTelegramTo(
                            chatId,
                            "❌ Player is not online: "
                                    + tpName
                    );
                    break;
                }

                World world =
                        tpPlayer.getWorld();

                tpPlayer.teleport(
                        world.getSpawnLocation()
                );

                sendTelegramTo(
                        chatId,
                        "📍 Teleported "
                                + tpName
                                + " to world spawn."
                );
                break;

            // /weather <clear/rain>
            case "/weather":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /weather <clear/rain>"
                    );
                    break;
                }

                String weather =
                        parts[1].trim().toLowerCase();

                if (weather.equals("clear")) {

                    for (World w : Bukkit.getWorlds()) {
                        w.setStorm(false);
                        w.setThundering(false);
                    }

                    sendTelegramTo(
                            chatId,
                            "☀️ Weather changed to CLEAR."
                    );

                } else if (weather.equals("rain")) {

                    for (World w : Bukkit.getWorlds()) {
                        w.setStorm(true);
                        w.setThundering(false);
                    }

                    sendTelegramTo(
                            chatId,
                            "🌧️ Weather changed to RAIN."
                    );

                } else {

                    sendTelegramTo(
                            chatId,
                            "❌ Use: /weather clear OR /weather rain"
                    );
                }
                break;

            // /time <day/night>
            case "/time":

                if (parts.length < 2) {
                    sendTelegramTo(
                            chatId,
                            "Usage: /time <day/night>"
                    );
                    break;
                }

                String time =
                        parts[1].trim().toLowerCase();

                if (time.equals("day")) {

                    for (World w : Bukkit.getWorlds()) {
                        w.setTime(1000);
                    }

                    sendTelegramTo(
                            chatId,
                            "☀️ Time changed to DAY."
                    );

                } else if (time.equals("night")) {

                    for (World w : Bukkit.getWorlds()) {
                        w.setTime(13000);
                    }

                    sendTelegramTo(
                            chatId,
                            "🌙 Time changed to NIGHT."
                    );

                } else {

                    sendTelegramTo(
                            chatId,
                            "❌ Use: /time day OR /time night"
                    );
                }
                break;

            // /broadcast <message>
            case "/broadcast":

                if (parts.length < 2
                        || parts[1].trim().isBlank()) {

                    sendTelegramTo(
                            chatId,
                            "Usage: /broadcast <message>"
                    );
                    break;
                }

                String broadcastMessage =
                        parts[1].trim();

                Bukkit.broadcastMessage(
                        ChatColor.GOLD
                                + "📢 [ANNOUNCEMENT] "
                                + ChatColor.WHITE
                                + broadcastMessage
                );

                sendTelegramTo(
                        chatId,
                        "📢 Broadcast sent:\n"
                                + broadcastMessage
                );
                break;

            // /tps
            case "/tps":

                double[] tps =
                        Bukkit.getTPS();

                double currentTps =
                        Math.min(tps[0], 20.0);

                double oneMinute =
                        Math.min(tps[1], 20.0);

                double fiveMinute =
                        Math.min(tps[2], 20.0);

                sendTelegramTo(
                        chatId,
                        String.format(
                                "📊 Server TPS\n\n"
                                        + "⚡ Current: %.2f\n"
                                        + "1m: %.2f\n"
                                        + "5m: %.2f\n\n"
                                        + "Max TPS: 20.00",
                                currentTps,
                                oneMinute,
                                fiveMinute
                        )
                );
                break;

            default:
                sendTelegramTo(
                        chatId,
                        "❌ Unknown command.\n\n"
                                + "Use /help"
                );
                break;
        }
    }

    private void sendTelegram(String text) {
        sendTelegramTo(adminId, text);
    }

    private void sendTelegramTo(
            long chatId,
            String text
    ) {

        try {

            String encoded =
                    URLEncoder.encode(
                            text,
                            StandardCharsets.UTF_8
                    );

            String url =
                    "https://api.telegram.org/bot"
                            + botToken
                            + "/sendMessage?chat_id="
                            + chatId
                            + "&text="
                            + encoded;

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();

            httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

        } catch (Exception e) {

            getLogger().warning(
                    "Telegram send error: "
                            + e.getMessage()
            );
        }
    }
}
