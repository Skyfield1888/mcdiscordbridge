package com.skyfield.mcdiscordbridge.client.emoji;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** A texture (or sequence of textures) ready to render inline in chat.
 *  Emoji and stickers both implement this - the render mixin doesn't need
 *  to know which one it's drawing, just its current frame, pixel size, and
 *  how it should be aligned against the text line. */
public interface EmojiVisual {

    ResourceLocation currentFrame();
    int width();
    int height();

    /** True for large inline images (gif:) that should be top-aligned to the text
     *  line so their extra height only bleeds downward, into blank lines reserved
     *  for exactly that purpose (see ClientChatHandler.reserveExtraChatLines).
     *  False (default) centers small emoji/stickers on the text baseline like before. */
    default boolean topAligned() {
        return false;
    }

    record StaticVisual(ResourceLocation location, int width, int height, boolean topAligned) implements EmojiVisual {
        public StaticVisual(ResourceLocation location, int width, int height) {
            this(location, width, height, false);
        }

        @Override
        public ResourceLocation currentFrame() {
            return location;
        }
    }

    final class AnimatedVisual implements EmojiVisual {
        private final List<ResourceLocation> frames;
        private final int frameDurationMs;
        private final int width;
        private final int height;
        private final boolean topAligned;

        public AnimatedVisual(List<ResourceLocation> frames, int frameDurationMs, int width, int height) {
            this(frames, frameDurationMs, width, height, false);
        }

        public AnimatedVisual(List<ResourceLocation> frames, int frameDurationMs, int width, int height, boolean topAligned) {
            this.frames = frames;
            this.frameDurationMs = Math.max(frameDurationMs, 20);
            this.width = width;
            this.height = height;
            this.topAligned = topAligned;
        }

        @Override
        public ResourceLocation currentFrame() {
            long time = System.currentTimeMillis();
            int totalDuration = frames.size() * frameDurationMs;
            long timeInLoop = time % totalDuration;
            int frameIndex = (int) (timeInLoop / frameDurationMs);
            return frames.get(Math.min(frameIndex, frames.size() - 1));
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean topAligned() {
            return topAligned;
        }
    }
}