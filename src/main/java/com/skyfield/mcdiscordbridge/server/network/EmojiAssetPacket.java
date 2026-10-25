package com.skyfield.mcdiscordbridge.server.network;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client. A fully-resolved emoji: its registry key and raw PNG
 *  bytes. Clients never hit Discord's CDN or jsdelivr, and no longer need
 *  a codepoint - the key maps directly to a registered DynamicTexture. */
public record EmojiAssetPacket(String key, byte[] pngBytes) implements CustomPacketPayload {

    public static final Type<EmojiAssetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McDiscordBridge.MODID, "emoji_asset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EmojiAssetPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, EmojiAssetPacket::key,
                    ByteBufCodecs.byteArray(1_048_576), EmojiAssetPacket::pngBytes,
                    EmojiAssetPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}