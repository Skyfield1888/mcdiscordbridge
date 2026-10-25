package com.skyfield.mcdiscordbridge.server.network;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Client -> server. One slice of a screenshot PNG. */
public record ScreenshotChunkPacket(UUID id, int index, int total, byte[] data)
        implements CustomPacketPayload {

    public static final int CHUNK_SIZE = 30_000; // stays under the 32 KB serverbound limit

    public static final Type<ScreenshotChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McDiscordBridge.MODID, "screenshot_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotChunkPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ScreenshotChunkPacket::id,
                    ByteBufCodecs.VAR_INT, ScreenshotChunkPacket::index,
                    ByteBufCodecs.VAR_INT, ScreenshotChunkPacket::total,
                    ByteBufCodecs.byteArray(CHUNK_SIZE), ScreenshotChunkPacket::data,
                    ScreenshotChunkPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}