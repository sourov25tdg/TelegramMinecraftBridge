package com.sourov.telegrambridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();

            if (!root.get("ok").getAsBoolean()) {
                return;
            }

            JsonArray updates = root.getAsJsonArray("result");

            for (var element : updates) {
                JsonObject update = element.getAsJsonObject();

                updateOffset =
                        update.get("update_id").getAsInt() + 1;

                if (!update.has("message")) {
                    continue;
                }

                JsonObject message = update.getAsJsonObject("message");

                if (!message.has("from") || !message.has("chat")) {
                    continue;
                }

                long userId =
                        message.getAsJsonObject("from")
                                .get("id")
                                .getAsLong();

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

                String text = message.get("text").getAsString();

                handleCommand(chatId, text);
            }

        } catch (Exception e) {
            getLogger().warning(
                    "Telegram connection error: " + e.getMessage()
            );
        }
    }

    private void handleCommand(long chatId, String text) {

        if (text.equals("/start")) {
            sendTelegramTo(
                    chatId,
                    "🤖 Telegram Minecraft Bridge\n\n"
                            + "Commands:\n"
                            + "/status - Server status\n"
                            + "/players - Online players\n"
                            + "/say <message> - Send message to Minecraft"
            );
            return;
        }

        if (text.equals("/help")) {
            sendTelegramTo(
                    chatId,
                    "/status\n"
                            + "/players\n"
                            + "/say <message>"
            );
            return;
        }

        if (text.equals("/status")) {
            int online = Bukkit.getOnlinePlayers().size();

            sendTelegramTo(
                    chatId,
                    "🟢 Server Online\n\n"
                            + "👥 Players: "
                            + online
            );
            return;
        }

        if (text.equals("/players")) {
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
                    "👥 Online Players\n\n" + players
            );
            return;
        }

        if (text.startsWith("/say ")) {

            if (!getConfig().getBoolean("enable-say", true)) {
                return;
            }

            String message =
                    text.substring(5).trim();

            if (message.isBlank()) {
                return;
            }

            String finalMessage =
                    ChatColor.AQUA + "[Telegram] "
                            + ChatColor.WHITE
                            + message;

            Bukkit.getScheduler().runTask(
                    this,
                    () -> Bukkit.broadcastMessage(finalMessage)
            );

            sendTelegramTo(
                    chatId,
                    "✅ Sent to Minecraft:\n" + message
            );
        }
    }

    private void sendTelegram(String text) {
        sendTelegramTo(adminId, text);
    }

    private void sendTelegramTo(long chatId, String text) {

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
