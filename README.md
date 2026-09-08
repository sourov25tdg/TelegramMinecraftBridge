# TelegramMinecraftBridge

Paper 1.21.1 plugin that connects a Minecraft server to a Telegram bot without RCON.

## Build

Requires Java 21 and Maven.

    mvn clean package

The plugin JAR will be in:

    target/telegram-minecraft-bridge-1.0.0.jar

## Install

1. Put the JAR into the Paper server's `plugins` folder.
2. Start the server once.
3. Edit:
   `plugins/TelegramMinecraftBridge/config.yml`
4. Put your NEW BotFather token in `bot-token`.
5. Keep the admin user ID as your Telegram numeric ID.
6. Restart the server.

## Telegram commands

/start
/help
/status
/players
/say Hello Minecraft

Minecraft chat is forwarded to the configured Telegram admin chat.

## Important

Do not share your BotFather token. If a token was exposed, revoke it in BotFather and create a new one.
