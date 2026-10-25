package com.skyfield.mcdiscordbridge.server.network;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DiscordChatPayload(String author, String content) implements CustomPacketPayload {

    public static final Type<DiscordChatPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(McDiscordBridge.MODID, "discord_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiscordChatPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, DiscordChatPayload::author,
                    ByteBufCodecs.STRING_UTF8, DiscordChatPayload::content,
                    DiscordChatPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}