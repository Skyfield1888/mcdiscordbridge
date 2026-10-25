package com.skyfield.mcdiscordbridge.server.voicechat;

import discord4j.voice.AudioProvider;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class VoiceBridgeAudioProvider extends AudioProvider {

    // Each entry is one raw Opus packet from a Minecraft player
    private final BlockingQueue<byte[]> opusQueue = new LinkedBlockingQueue<>(200);

    public VoiceBridgeAudioProvider() {
        super(ByteBuffer.allocate(4096));
    }

    public void queueOpusPacket(byte[] opusData) {
        opusQueue.offer(opusData); // non-blocking, drops if full
    }

    @Override
    public boolean provide() {
        try {
            byte[] packet = opusQueue.poll(0, TimeUnit.MILLISECONDS);
            if (packet == null) return false;

            ByteBuffer buf = getBuffer();
            buf.clear();
            buf.put(packet);
            buf.flip();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}