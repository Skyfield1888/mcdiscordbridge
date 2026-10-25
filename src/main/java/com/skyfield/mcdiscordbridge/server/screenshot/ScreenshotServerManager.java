package com.skyfield.mcdiscordbridge.server.screenshot;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import com.skyfield.mcdiscordbridge.server.discord.DiscordBotManager;
import com.skyfield.mcdiscordbridge.server.network.ScreenshotChunkPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ScreenshotServerManager {

    private static final int MAX_CHUNKS = 320;            // ~9.6 MB, under Discord's 10 MB limit
    private static final int MAX_BYTES = 9_000_000;
    private static final long COOLDOWN_MS = 10_000;
    private static final long TIMEOUT_MS = 60_000;

    private static final class Upload {
        final UUID id;
        final byte[][] parts;
        final boolean rejected;
        final long started = System.currentTimeMillis();
        int received;

        Upload(UUID id, int total, boolean rejected) {
            this.id = id;
            this.parts = rejected ? null : new byte[total][];
            this.rejected = rejected;
        }
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

    private ScreenshotServerManager() {}

    public static synchronized void handle(ServerPlayer player, ScreenshotChunkPacket p) {
        long now = System.currentTimeMillis();
        UPLOADS.values().removeIf(u -> now - u.started > TIMEOUT_MS);

        if (p.total() < 1 || p.total() > MAX_CHUNKS || p.index() < 0 || p.index() >= p.total()
                || p.data().length > ScreenshotChunkPacket.CHUNK_SIZE) return;

        UUID playerId = player.getUUID();
        Upload up = UPLOADS.get(playerId);

        if (up == null || !up.id.equals(p.id())) {
            DiscordBotManager bot = McDiscordBridge.getDiscordBotManager();
            boolean reject = false;
            if (bot == null) {
                player.sendSystemMessage(Component.literal("§c[McDiscordBridge] The Discord bridge isn't running."));
                reject = true;
            } else if (now - LAST_SENT.getOrDefault(playerId, 0L) < COOLDOWN_MS) {
                player.sendSystemMessage(Component.literal("§c[McDiscordBridge] Wait a few seconds between screenshots."));
                reject = true;
            }
            up = new Upload(p.id(), p.total(), reject);
            UPLOADS.put(playerId, up);
        }

        if (up.rejected) return;
        if (up.parts.length != p.total() || up.parts[p.index()] != null) return;

        up.parts[p.index()] = p.data();
        up.received++;
        if (up.received < up.parts.length) return;

        UPLOADS.remove(playerId);

        int size = 0;
        for (byte[] part : up.parts) size += part.length;
        if (size > MAX_BYTES) {
            player.sendSystemMessage(Component.literal("§c[McDiscordBridge] Screenshot is too large."));
            return;
        }

        byte[] png = new byte[size];
        int off = 0;
        for (byte[] part : up.parts) {
            System.arraycopy(part, 0, png, off, part.length);
            off += part.length;
        }

        // PNG magic bytes - refuse to relay arbitrary files
        if (size < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            player.sendSystemMessage(Component.literal("§c[McDiscordBridge] That isn't a PNG image."));
            return;
        }

        LAST_SENT.put(playerId, now);
        McDiscordBridge.getDiscordBotManager().sendScreenshot(player.getName().getString(), png);
        player.sendSystemMessage(Component.literal("§a[McDiscordBridge]§r Screenshot sent to Discord."));
    }
}