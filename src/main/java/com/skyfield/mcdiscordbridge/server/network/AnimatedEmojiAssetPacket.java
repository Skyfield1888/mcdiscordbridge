package com.skyfield.mcdiscordbridge.server.network;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Server -> client. All frames of an animated emoji or sticker, plus the
 *  per-frame duration. Client decodes each frame into its own texture and
 *  cycles between them based on wall-clock time (see AnimatedVisual). */
public record AnimatedEmojiAssetPacket(String key, List<byte[]> frames, int frameDurationMs)
        implements CustomPacketPayload {

    public static final Type<AnimatedEmojiAssetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McDiscordBridge.MODID, "animated_emoji_asset"));

    private static final int MAX_FRAME_BYTES = 2_097_152; // 2 MiB ceiling per frame
    private static final int MAX_FRAMES = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimatedEmojiAssetPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.key());
                buf.writeVarInt(packet.frameDurationMs());
                buf.writeVarInt(packet.frames().size());
                for (byte[] frame : packet.frames()) {
                    ByteBufCodecs.byteArray(MAX_FRAME_BYTES).encode(buf, frame);
                }
            },
            buf -> {
                String key = ByteBufCodecs.STRING_UTF8.decode(buf);
                int frameDuration = buf.readVarInt();
                int count = Math.min(buf.readVarInt(), MAX_FRAMES);
                List<byte[]> frames = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    frames.add(ByteBufCodecs.byteArray(MAX_FRAME_BYTES).decode(buf));
                }
                return new AnimatedEmojiAssetPacket(key, frames, frameDuration);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}