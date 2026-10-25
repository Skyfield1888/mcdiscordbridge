package com.skyfield.mcdiscordbridge.server.voicechat;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DiscordToMinecraftAudioSink {

    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono, required by SVC

    private final BlockingQueue<short[]> pcmQueue = new LinkedBlockingQueue<>(50);
    private short[] carry = new short[0]; // leftover samples between packets

    /**
     * Call this with PCM already decoded from Opus.
     * @param decodedPcm mono or stereo-interleaved 48kHz PCM shorts
     * @param stereo true if decodedPcm is stereo-interleaved (L,R,L,R,...)
     */
    public synchronized void pushPcm(short[] decodedPcm, boolean stereo) {
        short[] mono;

        if (stereo) {
            int monoSamples = decodedPcm.length / 2;
            mono = new short[carry.length + monoSamples];
            System.arraycopy(carry, 0, mono, 0, carry.length);
            for (int i = 0; i < monoSamples; i++) {
                short left = decodedPcm[i * 2];
                short right = decodedPcm[i * 2 + 1];
                mono[carry.length + i] = (short) ((left + right) / 2);
            }
        } else {
            mono = new short[carry.length + decodedPcm.length];
            System.arraycopy(carry, 0, mono, 0, carry.length);
            System.arraycopy(decodedPcm, 0, mono, carry.length, decodedPcm.length);
        }

        int offset = 0;
        while (mono.length - offset >= FRAME_SIZE) {
            short[] frame = new short[FRAME_SIZE];
            System.arraycopy(mono, offset, frame, 0, FRAME_SIZE);
            pcmQueue.offer(frame); // drop if full rather than block the network thread
            offset += FRAME_SIZE;
        }

        carry = Arrays.copyOfRange(mono, offset, mono.length);
    }

    /** Called by the SVC AudioPlayer's Supplier every 20ms. */
    public short[] pollFrame() {
        short[] frame = pcmQueue.poll();
        if (frame == null) {
            return new short[FRAME_SIZE]; // silence — keep the player alive instead of stopping it
        }
        return frame;
    }
}