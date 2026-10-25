package com.skyfield.mcdiscordbridge.server;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment("--Discord Bot configuration--")
            .comment("Bot_Token : Your Discord Bot Token :")
            .define("token", "Bot_Token");

    public static final ModConfigSpec.ConfigValue<String> CHANNEL_ID = BUILDER
            .comment("Channel ID : the chanel id where you want to send/receive your minecraft chat in the guild specified before")
            .define("channel_id","000000000000000000");


    public static final ModConfigSpec.ConfigValue<String> VOICE_CHANNEL_ID = BUILDER
            .comment("The ID of the Discord voice channel to bridge with Minecraft voice chat")
            .define("voice_channel_id", "000000000000000000");

    static final ModConfigSpec SPEC = BUILDER.build();
}
