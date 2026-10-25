package com.skyfield.mcdiscordbridge.server.voicechat;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.opus4j.UnknownPlatformException;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ForgeVoicechatPlugin
public class VoiceBridgePlugin implements VoicechatPlugin {

    public static final String GROUP_NAME = "Discord Link";

    // Singleton so DiscordBotManager can grab it
    public static VoiceBridgePlugin INSTANCE;

    private VoicechatServerApi serverApi;
    private Group globalGroup;

    // Minecraft -> Discord: fed player mic audio, drained by VoiceBridgeAudioProvider
    private volatile VoiceBridgeAudioProvider audioProvider;

    // Discord -> Minecraft: decoded PCM buffer + channel it gets played on
    private DiscordToMinecraftAudioSink discordSink;
    private OpusDecoder discordDecoder; // 48kHz stereo, matches Discord's voice format
    private StaticAudioChannel discordChannel;
    private AudioPlayer discordAudioPlayer;

    // Track connections so we can remove them as targets on disconnect,
    // even if the SVC-side connection registry no longer has them by then.
    private final Map<UUID, VoicechatConnection> activeConnections = new ConcurrentHashMap<>();

    public VoiceBridgePlugin() {
        INSTANCE = this;
    }

    @Override
    public String getPluginId() {
        return McDiscordBridge.MODID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);   // NEW
        registration.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup); // NEW
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (discordChannel == null || globalGroup == null) return;
        if (!event.getGroup().getId().equals(globalGroup.getId())) return; // not our group, ignore

        discordChannel.addTarget(event.getConnection());
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (discordChannel == null || globalGroup == null) return;
        if (!event.getGroup().getId().equals(globalGroup.getId())) return; // not our group, ignore

        discordChannel.removeTarget(event.getConnection());
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        globalGroup = serverApi.groupBuilder()
                .setName(GROUP_NAME)
                .setPersistent(true)
                .setType(Group.Type.OPEN)
                .build();

        // Discord -> Minecraft playback setup
        discordSink = new DiscordToMinecraftAudioSink();

        try {
            discordDecoder = new OpusDecoder(48000, 2); // 48kHz, stereo — matches Discord's voice format
        } catch (IOException | UnknownPlatformException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to load opus4j native decoder: " + e.getMessage());
            discordDecoder = null; // Discord -> Minecraft audio will be disabled, but the rest of the bridge still works
        }

        UUID channelId = UUID.randomUUID();
        discordChannel = serverApi.createStaticAudioChannel(channelId);
        if (discordChannel != null) {
            OpusEncoder encoder = serverApi.createEncoder();
            discordAudioPlayer = serverApi.createAudioPlayer(
                    discordChannel, encoder, discordSink::pollFrame);
            discordAudioPlayer.startPlaying();
        } else {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to create static audio channel for Discord playback.");
        }
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        if (discordAudioPlayer != null) {
            discordAudioPlayer.stopPlaying();
            discordAudioPlayer = null;
        }
        if (discordDecoder != null) {
            discordDecoder.close();
            discordDecoder = null;
        }
        discordChannel = null;
        discordSink = null;
        activeConnections.clear();
        serverApi = null;
        globalGroup = null;
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (serverApi == null || globalGroup == null) return;
        VoicechatConnection connection = event.getConnection();
        if (connection.getGroup() == null) {
            connection.setGroup(globalGroup);
        }

        activeConnections.put(connection.getPlayer().getUuid(), connection);

        if (discordChannel != null) {
            discordChannel.addTarget(connection); // let them hear Discord audio too
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerUuid = event.getPlayerUuid();
        VoicechatConnection connection = activeConnections.remove(playerUuid);
        if (connection != null && discordChannel != null) {
            discordChannel.removeTarget(connection);
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (audioProvider == null || globalGroup == null) return;

        // Only forward packets from players who are in the global group
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;
        Group senderGroup = sender.getGroup();
        if (senderGroup == null || !senderGroup.getId().equals(globalGroup.getId())) return;

        MicrophonePacket packet = event.getPacket();
        byte[] opusData = packet.getOpusEncodedData();
        if (opusData != null && opusData.length > 0) {
            audioProvider.queueOpusPacket(opusData);
        }
    }

    // --- Called by DiscordBotManager ---

    public void setAudioProvider(VoiceBridgeAudioProvider provider) {
        this.audioProvider = provider;
    }

    public DiscordToMinecraftAudioSink getDiscordSink() {
        return discordSink;
    }

    public OpusDecoder getDiscordDecoder() {
        return discordDecoder;
    }

    public VoicechatServerApi getServerApi() {
        return serverApi;
    }

    public Group getGlobalGroup() {
        return globalGroup;
    }
}