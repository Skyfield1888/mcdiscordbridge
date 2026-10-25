package com.skyfield.mcdiscordbridge.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import com.skyfield.mcdiscordbridge.server.network.ScreenshotChunkPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

@EventBusSubscriber(modid = McDiscordBridge.MODID, value = Dist.CLIENT)
public final class ScreenshotClientHandler {

    private static final int MAX_BYTES = 9_000_000;

    private ScreenshotClientHandler() {}

    /** Adds a clickable button to the "Saved screenshot as ..." chat line. */
    @SubscribeEvent
    public static void onScreenshot(ScreenshotEvent event) {
        File file = event.getScreenshotFile();

        // Vanilla's default "Saved screenshot as <file>" line (clickable, opens the file)
        Component fileLink = Component.literal(file.getName())
                .withStyle(ChatFormatting.UNDERLINE)
                .withStyle(s -> s.withClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())));
        Component original = event.getResultMessage() != null
                ? event.getResultMessage()
                : Component.translatable("screenshot.success", fileLink);

        MutableComponent button = Component.literal("[Send to Discord]").withStyle(s -> s
                .withColor(0x7289DA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/mcdcscreenshot " + file.getName()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Post this screenshot in the Discord channel"))));

        event.setResultMessage(original.copy().append(Component.literal("\n")).append(button));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mcdcscreenshot")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    send(StringArgumentType.getString(ctx, "file"));
                                    return 1;
                                })));
    }

    private static void send(String fileName) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("screenshots").toAbsolutePath().normalize();
        Path file = dir.resolve(fileName).normalize();

        // Only allow files inside the screenshots folder
        if (!file.startsWith(dir) || !fileName.toLowerCase().endsWith(".png") || !Files.isRegularFile(file)) {
            say("§c[McDiscordBridge] Screenshot not found.");
            return;
        }

        Thread t = new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_BYTES) {
                    say("§c[McDiscordBridge] Screenshot is too large (max ~9 MB).");
                    return;
                }
                UUID id = UUID.randomUUID();
                int total = (bytes.length + ScreenshotChunkPacket.CHUNK_SIZE - 1) / ScreenshotChunkPacket.CHUNK_SIZE;

                mc.execute(() -> {
                    try {
                        for (int i = 0; i < total; i++) {
                            int from = i * ScreenshotChunkPacket.CHUNK_SIZE;
                            int to = Math.min(bytes.length, from + ScreenshotChunkPacket.CHUNK_SIZE);
                            PacketDistributor.sendToServer(
                                    new ScreenshotChunkPacket(id, i, total, Arrays.copyOfRange(bytes, from, to)));
                        }
                        say("§7[McDiscordBridge] Uploading screenshot...");
                    } catch (Exception e) {
                        say("§c[McDiscordBridge] This server doesn't support screenshot sharing.");
                    }
                });
            } catch (Exception e) {
                McDiscordBridge.LOGGER.error("[McDiscordBridge] Could not read screenshot", e);
                say("§c[McDiscordBridge] Could not read the screenshot.");
            }
        }, "mcdiscordbridge-screenshot");
        t.setDaemon(true);
        t.start();
    }

    private static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
        });
    }
}