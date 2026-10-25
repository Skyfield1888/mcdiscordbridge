package com.skyfield.mcdiscordbridge.server.voicechat;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import de.maxhenkel.opus4j.OpusDecoder;
import discord4j.voice.AudioReceiver;

import java.nio.ByteBuffer;

public class DiscordAudioReceiver extends AudioReceiver {

    private final DiscordToMinecraftAudioSink sink;
    private final OpusDecoder decoder;

    public DiscordAudioReceiver(DiscordToMinecraftAudioSink sink, OpusDecoder decoder) {
        super(ByteBuffer.allocate(4096));
        this.sink = sink;
        this.decoder = decoder;
    }

    @Override
    public void receive(char sequence, int timestamp, int ssrc, byte[] opusData) {
        if (decoder == null || sink == null) return;
        if (opusData == null || opusData.length < 4) return;

        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(opusData.length, 16); i++) {
            hex.append(String.format("%02X ", opusData[i]));
        }


        try {
            byte[] payload = stripRtpExtensionIfPresent(opusData);
            if (payload.length == 0) return;

            short[] pcm = decoder.decode(payload);
            if (pcm != null && pcm.length > 0) {
                sink.pushPcm(pcm, true);
            }
        } catch (Exception e) {
            McDiscordBridge.LOGGER.warn("[McDiscordBridge] Failed to decode/push voice packet: " + e.getMessage());
        }
    }

    private static byte[] stripRtpExtensionIfPresent(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xFF) == 0xBE && (data[1] & 0xFF) == 0xDE) {
            return java.util.Arrays.copyOfRange(data, 4, data.length);
        }
        return data; // no extension marker present, use as-is
    }
}