package com.skyfield.mcdiscordbridge.client.emoji;

import com.skyfield.mcdiscordbridge.server.network.DiscordChatPayload;
import com.skyfield.mcdiscordbridge.server.emoji.EmojiKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;

import java.util.ArrayList;
import java.util.List;

public class ClientChatHandler {

    private record Span(int start, int end, String key, String fallback) {}

    public static void handle(DiscordChatPayload payload) {
        String content = payload.content();

        List<Span> spans = new ArrayList<>();
        for (EmojiKeys.CustomEmojiRef ref : EmojiKeys.findCustomEmoji(content)) {
            String name = extractName(content, ref);
            spans.add(new Span(ref.start(), ref.end(), ref.key(), ":" + name + ":"));
        }
        for (EmojiKeys.StickerRef ref : EmojiKeys.findStickers(content)) {
            spans.add(new Span(ref.start(), ref.end(), ref.key(), "[" + ref.name() + "]"));
        }
        for (EmojiKeys.GifEmbedRef ref : EmojiKeys.findGifEmbeds(content)) {
            spans.add(new Span(ref.start(), ref.end(), ref.key(), "[gif]"));
        }

        spans.sort((a, b) -> Integer.compare(a.start(), b.start()));

        StringBuilder rebuilt = new StringBuilder();
        int last = 0;
        for (Span span : spans) {
            rebuilt.append(content, last, span.start());
            appendTokenOrFallback(rebuilt, span.key(), span.fallback());
            last = span.end();
        }
        rebuilt.append(content.substring(last));
        content = renderTwemoji(rebuilt.toString());

        MutableComponent text = Component.literal("[Discord] ").withStyle(s -> s.withColor(0x7289DA))
                .append(Component.literal(payload.author() + ": ").withStyle(s -> s.withColor(0xFFFFFF)));

        for (String part : content.split(" ")) {
            if (part.matches("https?://\\S+")) {
                String displayText = "[link]";
                String lower = part.toLowerCase();
                if (lower.contains("cdn.discordapp.com") || lower.matches(".*\\.(png|jpg|jpeg|gif)(\\?.*)?$"))
                    displayText = "[image]";
                else if (lower.contains("youtu")) displayText = "[youtube]";
                else if (lower.contains("discord.gg")) displayText = "[invite]";
                else if (lower.contains("discord.com/channels/")) displayText = "[channel]";
                text.append(Component.literal(displayText).withStyle(s -> s
                        .withColor(0x00AAFF).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(part)))));
                text.append(Component.literal(" "));
            } else {
                text.append(Component.literal(part + " "));
            }
        }

        Minecraft.getInstance().gui.getChat().addMessage(text);
        reserveExtraChatLines(spans);
    }

    /** Images render taller than one chat line (see ClientTextureCache.displaySize).
     *  Vanilla chat stacks lines at a fixed height with no idea our line is actually
     *  taller, so without this the next message(s) would render on top of the bottom
     *  of the image. Padding in blank lines "claims" that vertical space up front -
     *  the image visually bleeds down into these blank slots instead of real content. */
    private static void reserveExtraChatLines(List<Span> spans) {
        int tallestHeight = 0;
        boolean sawUnresolvedGif = false;

        for (Span span : spans) {
            if (!span.key().startsWith("gif:")) continue;

            EmojiVisual visual = ClientTextureCache.getVisual(span.key());
            if (visual != null) {
                tallestHeight = Math.max(tallestHeight, visual.height());
            } else {
                sawUnresolvedGif = true; // asset packet hasn't landed yet - rare race, see below
            }
        }

        if (sawUnresolvedGif && tallestHeight == 0) {
            // Couldn't measure the real size - reserve for the maximum possible height
            // (see the 80px cap in ClientTextureCache.fitWithin) so we're never short.
            tallestHeight = 80;
        }
        if (tallestHeight <= 0) return;

        int lineHeight = Minecraft.getInstance().font.lineHeight + 2; // matches vanilla chat line spacing
        int extraLines = Math.max(0, (int) Math.ceil((tallestHeight - lineHeight) / (double) lineHeight));

        for (int i = 0; i < extraLines; i++) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.empty());
        }
    }

    private static String extractName(String content, EmojiKeys.CustomEmojiRef ref) {
        var m = EmojiKeys.CUSTOM_EMOJI.matcher(content.substring(ref.start(), ref.end()));
        return m.find() ? m.group(2) : "emoji";
    }

    private static String renderTwemoji(String input) {
        StringBuilder out = new StringBuilder();
        List<Integer> cluster = new ArrayList<>();
        for (int cp : input.codePoints().toArray()) {
            boolean isBase = EmojiKeys.isEmojiBase(cp);
            boolean isContinuation = cp == 0x200D || cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF);
            if (!cluster.isEmpty() && (isContinuation || isBase)) {
                cluster.add(cp);
            } else {
                flushTwemoji(cluster, out);
                cluster.clear();
                if (isBase) cluster.add(cp); else out.appendCodePoint(cp);
            }
        }
        flushTwemoji(cluster, out);
        return out.toString();
    }

    private static void flushTwemoji(List<Integer> cluster, StringBuilder out) {
        if (cluster.isEmpty()) return;
        String key = "twemoji:" + EmojiKeys.join(cluster);
        StringBuilder fallback = new StringBuilder();
        for (int cp : cluster) fallback.appendCodePoint(cp);
        appendTokenOrFallback(out, key, fallback.toString());
    }

    private static void appendTokenOrFallback(StringBuilder out, String key, String fallback) {
        Character token = ClientTextureCache.getToken(key);
        if (token != null) {
            out.append((char) token.charValue());
        } else {
            out.append(fallback);
            if (!ClientTextureCache.isKnown(key)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.skyfield.mcdiscordbridge.server.network.RequestMissingEmojiPacket(key));
            }
        }
    }
}