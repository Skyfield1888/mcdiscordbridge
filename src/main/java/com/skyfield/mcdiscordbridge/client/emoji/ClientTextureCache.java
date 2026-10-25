package com.skyfield.mcdiscordbridge.client.emoji;

import com.mojang.blaze3d.platform.NativeImage;
import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import com.skyfield.mcdiscordbridge.server.emoji.EmojiKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds live GPU visuals (static or animated) for resolved emoji and
 * stickers, keyed by registry key, plus a single-character PUA token per
 * key so chat's pre-render line-wrapping sees one narrow character instead
 * of a long key string. No font/resource pack, no disk writes on the
 * client - PNG bytes arrive over the network and become textures directly.
 */
public final class ClientTextureCache {

    private static final Map<String, EmojiVisual> VISUALS = new ConcurrentHashMap<>();
    private static final Map<String, Character> KEY_TO_TOKEN = new ConcurrentHashMap<>();
    private static final Map<Character, String> TOKEN_TO_KEY = new ConcurrentHashMap<>();
    private static int nextToken = 0xE000;

    private ClientTextureCache() {}

    /** Called (off the network thread) when an EmojiAssetPacket arrives. */
    public static void receiveStatic(String key, byte[] pngBytes) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(pngBytes));
            int w = image.getWidth();
            int h = image.getHeight();
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    McDiscordBridge.MODID, "chat_emoji/" + EmojiKeys.sanitize(key));

            int[] size = displaySize(key, w, h);
            boolean topAligned = key.startsWith("gif:");
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
                VISUALS.put(key, new EmojiVisual.StaticVisual(location, size[0], size[1], topAligned));
                assignToken(key);
            });
        } catch (IOException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to decode emoji asset " + key, e);
        }
    }

    /** Called (off the network thread) when an AnimatedEmojiAssetPacket arrives. */
    public static void receiveAnimated(String key, List<byte[]> framePngBytes, int frameDurationMs) {
        try {
            List<NativeImage> images = new ArrayList<>(framePngBytes.size());
            for (byte[] frame : framePngBytes) {
                images.add(NativeImage.read(new ByteArrayInputStream(frame)));
            }
            int w = images.get(0).getWidth();
            int h = images.get(0).getHeight();
            int[] size = displaySize(key, w, h);
            boolean topAligned = key.startsWith("gif:");

            Minecraft.getInstance().execute(() -> {
                List<ResourceLocation> locations = new ArrayList<>(images.size());
                for (int i = 0; i < images.size(); i++) {
                    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                            McDiscordBridge.MODID, "chat_emoji/" + EmojiKeys.sanitize(key) + "/" + i);
                    Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(images.get(i)));
                    locations.add(location);
                }
                VISUALS.put(key, new EmojiVisual.AnimatedVisual(locations, frameDurationMs, size[0], size[1], topAligned));
                assignToken(key);
            });
        } catch (IOException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to decode animated emoji asset " + key, e);
        }
    }

    /** Emoji render small (matches font line height); stickers render a bit bigger,
     *  matching "like an emoji, just bigger". GIFs/images render as a genuine inline
     *  image, fit within a larger box - see reserveExtraChatLines in ClientChatHandler
     *  for how the extra height avoids overlapping the chat lines below it. */
    private static int[] displaySize(String key, int nativeWidth, int nativeHeight) {
        if (key.startsWith("gif:")) {
            return fitWithin(nativeWidth, nativeHeight, 100, 80);
        }
        int target = key.startsWith("sticker:") ? 28 : 9;
        if (nativeWidth <= 0 || nativeHeight <= 0) return new int[]{target, target};
        if (nativeWidth >= nativeHeight) {
            int w = target;
            int h = Math.max(1, Math.round(target * (nativeHeight / (float) nativeWidth)));
            return new int[]{w, h};
        } else {
            int h = target;
            int w = Math.max(1, Math.round(target * (nativeWidth / (float) nativeHeight)));
            return new int[]{w, h};
        }
    }

    /** Scales to fit within maxW x maxH while preserving aspect ratio (can scale up or
     *  down - small thumbnails still end up a reasonable visible size in chat). */
    private static int[] fitWithin(int nativeWidth, int nativeHeight, int maxW, int maxH) {
        if (nativeWidth <= 0 || nativeHeight <= 0) return new int[]{maxW, maxH};
        double scale = Math.min(maxW / (double) nativeWidth, maxH / (double) nativeHeight);
        int w = Math.max(1, (int) Math.round(nativeWidth * scale));
        int h = Math.max(1, (int) Math.round(nativeHeight * scale));
        return new int[]{w, h};
    }

    private static synchronized void assignToken(String key) {
        if (KEY_TO_TOKEN.containsKey(key)) return;
        if (nextToken > 0xF8FF) {
            McDiscordBridge.LOGGER.warn("[McDiscordBridge] Ran out of PUA tokens this session, "
                    + "{} will render as fallback text only", key);
            return;
        }
        char token = (char) nextToken++;
        KEY_TO_TOKEN.put(key, token);
        TOKEN_TO_KEY.put(token, key);
    }

    public static boolean isKnown(String key) {
        return VISUALS.containsKey(key);
    }

    public static Character getToken(String key) {
        return KEY_TO_TOKEN.get(key);
    }

    public static EmojiVisual getVisual(String key) {
        return VISUALS.get(key);
    }

    public static EmojiVisual getByToken(char token) {
        String key = TOKEN_TO_KEY.get(token);
        return key == null ? null : VISUALS.get(key);
    }
}