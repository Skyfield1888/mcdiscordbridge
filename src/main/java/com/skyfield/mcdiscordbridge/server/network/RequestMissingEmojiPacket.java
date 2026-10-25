package com.skyfield.mcdiscordbridge.server.network;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "I don't have this key, please (re)send it." Covers a
 *  cleared local cache, or a chat line rendered before the join-sync landed. */
public record RequestMissingEmojiPacket(String key) implements CustomPacketPayload {

    public static final Type<RequestMissingEmojiPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McDiscordBridge.MODID, "request_missing_emoji"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMissingEmojiPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RequestMissingEmojiPacket::key,
                    RequestMissingEmojiPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}