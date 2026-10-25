package com.skyfield.mcdiscordbridge.server.emoji;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared logic for finding emoji references in chat text and turning them
 * into canonical registry keys ("discord:{id}" / "twemoji:{hex-cluster}").
 * The server uses this to know what to resolve before forwarding a message;
 * the client uses it to know what to look up when rendering one.
 */
public final class EmojiKeys {

    public static final Pattern CUSTOM_EMOJI = Pattern.compile("<(a)?:(\\w+):(\\d+)>");

    private EmojiKeys() {}

    public record CustomEmojiRef(String key, String id, boolean animated, int start, int end) {}

    public static List<CustomEmojiRef> findCustomEmoji(String content) {
        List<CustomEmojiRef> refs = new ArrayList<>();
        Matcher m = CUSTOM_EMOJI.matcher(content);
        while (m.find()) {
            boolean animated = m.group(1) != null;
            String id = m.group(3);
            refs.add(new CustomEmojiRef("discord:" + id, id, animated, m.start(), m.end()));
        }
        return refs;
    }

    /** Distinct Twemoji cluster keys present in the string (order of first appearance). */
    public static Set<String> findTwemojiKeys(String content) {
        Set<String> keys = new LinkedHashSet<>();
        List<Integer> cluster = new ArrayList<>();
        for (int cp : content.codePoints().toArray()) {
            boolean isBase = isEmojiBase(cp);
            boolean isContinuation = cp == 0x200D || cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF);
            if (!cluster.isEmpty() && (isContinuation || isBase)) {
                cluster.add(cp);
            } else {
                flush(cluster, keys);
                cluster.clear();
                if (isBase) cluster.add(cp);
            }
        }
        flush(cluster, keys);
        return keys;
    }

    private static void flush(List<Integer> cluster, Set<String> keys) {
        if (!cluster.isEmpty()) keys.add("twemoji:" + join(cluster));
    }

    public static String join(List<Integer> cps) {
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) {
            if (sb.length() > 0) sb.append('-');
            sb.append(Integer.toHexString(cp));
        }
        return sb.toString();
    }

    public static String stripFe0f(String hexJoined) {
        StringBuilder sb = new StringBuilder();
        for (String p : hexJoined.split("-")) {
            if (p.equals("fe0f")) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(p);
        }
        return sb.toString();
    }

    public static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x2300 && cp <= 0x23FF);
    }

    public static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
    public static final Pattern STICKER_TOKEN = Pattern.compile("<s:(\\w+):(\\d+)>");

    public record StickerRef(String key, String id, String name, int start, int end) {}

    public static List<StickerRef> findStickers(String content) {
        List<StickerRef> refs = new ArrayList<>();
        Matcher m = STICKER_TOKEN.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            String id = m.group(2);
            refs.add(new StickerRef("sticker:" + id, id, name, m.start(), m.end()));
        }
        return refs;
    }

    public static String stickerToken(String name, String id) {
        return "<s:" + name + ":" + id + ">";
    }

    public static final Pattern GIF_EMBED_TOKEN = Pattern.compile("<g:(\\w+)>");

    public record GifEmbedRef(String key, String hash, int start, int end) {}

    public static List<GifEmbedRef> findGifEmbeds(String content) {
        List<GifEmbedRef> refs = new ArrayList<>();
        Matcher m = GIF_EMBED_TOKEN.matcher(content);
        while (m.find()) {
            String hash = m.group(1);
            refs.add(new GifEmbedRef("gif:" + hash, hash, m.start(), m.end()));
        }
        return refs;
    }

    public static String gifEmbedToken(String hash) {
        return "<g:" + hash + ">";
    }
}