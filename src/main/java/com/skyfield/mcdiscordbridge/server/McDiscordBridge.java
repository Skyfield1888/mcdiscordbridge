package com.skyfield.mcdiscordbridge.server;

import com.skyfield.mcdiscordbridge.client.emoji.ClientChatHandler;
import com.skyfield.mcdiscordbridge.client.emoji.ClientTextureCache;
import com.skyfield.mcdiscordbridge.server.emoji.EmojiServerManager;
import com.skyfield.mcdiscordbridge.server.network.*;
import com.skyfield.mcdiscordbridge.server.discord.DiscordBotManager;
import com.skyfield.mcdiscordbridge.server.discord.LinkManager;
import com.skyfield.mcdiscordbridge.server.screenshot.ScreenshotServerManager;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import java.util.UUID;

@Mod(McDiscordBridge.MODID)
public class McDiscordBridge {
    public static final String MODID = "mcdiscordbridge";
    public static final Logger LOGGER = LogUtils.getLogger();

     private static DiscordBotManager discordBotManager;

    public McDiscordBridge(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MODID).versioned("1");

        registrar.playToClient(
                DiscordChatPayload.TYPE,
                DiscordChatPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientChatHandler.handle(payload))
        );

        registrar.playToClient(
                EmojiAssetPacket.TYPE,
                EmojiAssetPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientTextureCache.receiveStatic(payload.key(), payload.pngBytes()))
        );

        registrar.playToClient(
                AnimatedEmojiAssetPacket.TYPE,
                AnimatedEmojiAssetPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientTextureCache.receiveAnimated(payload.key(), payload.frames(), payload.frameDurationMs()))
        );
        registrar.playToServer(
                ScreenshotChunkPacket.TYPE,
                ScreenshotChunkPacket.STREAM_CODEC,
                (payload, context) -> ScreenshotServerManager.handle((ServerPlayer) context.player(), payload)
        );
        registrar.playToServer(
                RequestMissingEmojiPacket.TYPE,
                RequestMissingEmojiPacket.STREAM_CODEC,
                (payload, context) -> EmojiServerManager.handleMissingRequest(
                        (ServerPlayer) context.player(), payload.key())
        );
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        Configurator.setLevel("reactor.netty", Level.INFO);
        Configurator.setLevel("discord4j", Level.INFO);
        Configurator.setLevel("voicechat", Level.INFO);
    }

    public static DiscordBotManager getDiscordBotManager() {
        return discordBotManager;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[McDiscordBridge] Server is starting - initializing Discord bot.");

        String token = Config.TOKEN.get();
        String channelId = Config.CHANNEL_ID.get();

        if (token.equals("Bot_Token")) {
            LOGGER.warn("[McDiscordBridge] No valid Discord token in config. Bot will not start.");
            return;
        }
        if (channelId.equals("000000000000000000")) {
            LOGGER.warn("[McDiscordBridge] Invalid Discord channel ID in config.");
            return;
        }
        EmojiServerManager.init();
        Thread botThread = new Thread(() -> {
            try {
                String voiceChannelId = Config.VOICE_CHANNEL_ID.get();
                discordBotManager = new DiscordBotManager(token, channelId, voiceChannelId);
                discordBotManager.start();
                LOGGER.info("[McDiscordBridge] Discord bot started successfully.");
            } catch (Exception e) {
                LOGGER.error("Failed to start Discord bot", e);
            }
        },"mcdiscordbridge-bot");
        botThread.setContextClassLoader(McDiscordBridge.class.getClassLoader());
        botThread.setDaemon(true);
        botThread.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (discordBotManager != null) {
            LOGGER.info("[McDiscordBridge] Server stopping - shutting down Discord bot.");
            discordBotManager.shutdown();
            discordBotManager = null;
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (discordBotManager != null) {
            String playerName = event.getUsername();
            String message = event.getMessage().getString();
            if (message.contains("!-")) {
                return;
            }
            String processedMessage = message;

            // Match @PlayerName mentions and convert to real Discord pings
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                    String mentionToken = "@" + onlinePlayer.getName().getString();
                    if (processedMessage.contains(mentionToken)) {
                        UUID uuid = onlinePlayer.getUUID();
                        LinkManager.LinkEntry link = LinkManager.getAllLinks().stream()
                                .filter(l -> l.minecraftUuid.equals(uuid.toString()))
                                .findFirst().orElse(null);
                        if (link != null) {
                            processedMessage = processedMessage.replace(
                                    mentionToken, "<@" + link.discordId + ">");
                        }
                    }
                }
            }

            discordBotManager.sendMessageToDiscord("<**" + playerName + "**> " + processedMessage);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(
                net.minecraft.commands.Commands.literal("mcdcbridge")
                        .then(net.minecraft.commands.Commands.literal("link")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String code = LinkManager.generateCode(player);
                                    if (code.equals("ALREADY_LINKED")) {
                                        player.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal("§cYou are already linked to a Discord account!")
                                        );
                                    } else {
                                        player.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal("§aYour Discord link code: §e" + code)
                                        );
                                        player.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal("§7Use `/link " + code + "` in Discord to finish linking.")
                                        );
                                    }
                                    return 1;
                                })
                        )
        );
        dispatcher.register(
                net.minecraft.commands.Commands.literal("mcdcbridge")
                        .then(net.minecraft.commands.Commands.literal("reload")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (discordBotManager != null) {
                                        LOGGER.info("[McDiscordBridge] Server stopping - shutting down Discord bot.");
                                        discordBotManager.shutdown();
                                        discordBotManager = null;
                                    }
                                    String token = Config.TOKEN.get();
                                    String channelId = Config.CHANNEL_ID.get();
                                    boolean canStart = true;
                                    if (token.equals("Bot_Token")) {
                                        LOGGER.warn("[McDiscordBridge] No valid Discord token in config. Bot will not start.");
                                        player.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal("§a[McDiscordBridge]§r No valid Discord token in config. Bot will not start.")
                                        );
                                        canStart = false;
                                    }
                                    if (channelId.equals("000000000000000000")) {
                                        LOGGER.warn("[McDiscordBridge] Invalid Discord channel ID in config.");
                                        player.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal("§a[McDiscordBridge]§r Invalid Discord channel ID in config. Bot will not start.")
                                        );
                                        canStart = false;
                                    }
                                    if (canStart){
                                        try {
                                            String voiceChannelId = Config.VOICE_CHANNEL_ID.get();
                                            discordBotManager = new DiscordBotManager(token, channelId, voiceChannelId);
                                            discordBotManager.start();
                                            LOGGER.info("[McDiscordBridge] Discord bot started successfully.");
                                            player.sendSystemMessage(
                                                    net.minecraft.network.chat.Component.literal("§a[McDiscordBridge]§g Discord bot started successfully.")
                                            );
                                        } catch (Exception e) {
                                            LOGGER.error("Failed to start Discord bot", e);
                                            player.sendSystemMessage(
                                                    net.minecraft.network.chat.Component.literal("§a[McDiscordBridge]§r Failed to start Discord bot !")
                                            );
                                        }
                                    }
                                    return 1;
                                })
                        )
        );
    }
    public static void playNotifySound(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.UI_BUTTON_CLICK, // sound
                SoundSource.MASTER,          // category
                player.getX(), player.getY(), player.getZ(), // coordinates
                1.0f, // volume
                1.0f, // pitch
                1L   // seed, must be long
        ));
    }
    @SubscribeEvent
    public void onPlayerJoin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EmojiServerManager.sendKnownEmojiTo(player);
        }
    }
}
