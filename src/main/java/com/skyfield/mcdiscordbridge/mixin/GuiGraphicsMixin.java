package com.skyfield.mcdiscordbridge.mixin;

import com.skyfield.mcdiscordbridge.client.emoji.ClientTextureCache;
import com.skyfield.mcdiscordbridge.client.emoji.EmojiVisual;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    // Pre-1.21.6 GuiGraphics.blit signature - no RenderPipeline argument.
    @Shadow
    public abstract void blit(ResourceLocation atlasLocation, int x, int y,
                              float uOffset, float vOffset, int width, int height,
                              int textureWidth, int textureHeight);

    /**
     * Rewrites the FormattedCharSequence argument of drawString before vanilla
     * renders it: finds single-character PUA tokens (emoji or stickers),
     * draws each one's current frame directly via blit at the right x
     * position sized to its own width/height, and reserves that width with
     * space placeholders so the remaining text still lays out correctly.
     */
    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
            at = @At("HEAD"),
            argsOnly = true
    )
    private FormattedCharSequence handleDiscordEmojiTokens(FormattedCharSequence capturedText, Font font,
                                                           FormattedCharSequence text, int x, int y,
                                                           int color, boolean dropShadow) {
        if (capturedText == null || capturedText == FormattedCharSequence.EMPTY) return capturedText;

        List<Integer> codepoints = new ArrayList<>();
        capturedText.accept((index, style, codePoint) -> {
            codepoints.add(codePoint);
            return true;
        });
        if (codepoints.isEmpty()) return capturedText;

        boolean anyToken = false;
        for (int cp : codepoints) {
            if (cp >= 0xE000 && cp <= 0xF8FF && ClientTextureCache.getByToken((char) cp) != null) {
                anyToken = true;
                break;
            }
        }
        if (!anyToken) return capturedText; // cheap early exit - no emoji/sticker on this line

        int spaceWidth = Math.max(1, font.width(" "));
        List<FormattedCharSequence> result = new ArrayList<>();
        int currentX = x;
        int segmentStart = 0;

        for (int i = 0; i < codepoints.size(); i++) {
            int cp = codepoints.get(i);
            EmojiVisual visual = (cp >= 0xE000 && cp <= 0xF8FF) ? ClientTextureCache.getByToken((char) cp) : null;
            if (visual == null) continue;

            if (i > segmentStart) {
                FormattedCharSequence before = subFormattedCharSequence(capturedText, segmentStart, i);
                result.add(before);
                currentX += font.width(before) + 1;
            }

            int w = visual.width();
            int h = visual.height();
            int yOffset;
            if (visual.topAligned()) {
                // Large inline images (gif:): top-align to the text line so the extra
                // height only bleeds downward, into the blank lines ClientChatHandler
                // reserves below the message for exactly this. Centering these instead
                // would also bleed upward into the previous (already-rendered, un-reservable) line.
                yOffset = y - 1;
            } else {
                yOffset = y - (h - 9) / 2 - 1; // roughly centers small emoji/stickers on the text baseline
            }
            blit(visual.currentFrame(), currentX, yOffset, 0, 0, w, h, w, h);

            int spacesToReserve = Math.max(1, (int) Math.ceil(w / (double) spaceWidth));
            for (int s = 0; s < spacesToReserve; s++) {
                result.add(FormattedCharSequence.codepoint(' ', Style.EMPTY));
            }
            currentX += w + 1;

            segmentStart = i + 1;
        }

        if (segmentStart < codepoints.size()) {
            result.add(subFormattedCharSequence(capturedText, segmentStart, codepoints.size()));
        }

        return FormattedCharSequence.composite(result);
    }

    private static FormattedCharSequence subFormattedCharSequence(FormattedCharSequence text, int start, int end) {
        if (start >= end || start < 0) return FormattedCharSequence.EMPTY;

        List<FormattedCharSequence> parts = new ArrayList<>();
        AtomicInteger index = new AtomicInteger();

        text.accept((i, style, codePoint) -> {
            if (index.get() >= start && index.get() < end)
                parts.add(FormattedCharSequence.codepoint(codePoint, style));
            index.getAndIncrement();
            return true;
        });

        return FormattedCharSequence.composite(parts);
    }
}