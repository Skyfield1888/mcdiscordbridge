package com.skyfield.mcdiscordbridge.server.emoji;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reads every frame of an animated WebP into a sequence of fully-composited
 * PNG frames, using TwelveMonkeys' WebP ImageIO plugin (com.twelvemonkeys.imageio:imageio-webp).
 * Unlike GifFrameDecoder, this does NOT do manual disposal/compositing -
 * ImageReader.read(i) is expected to return a complete canvas-sized frame per
 * the standard ImageIO contract, so no extra work is needed there.
 *
 * Known limitation: as of writing, TwelveMonkeys does not expose per-frame
 * animation duration via ImageIO metadata for WebP - see
 * https://github.com/haraldk/TwelveMonkeys/issues/711 (open, confirmed by the
 * plugin author). We use a fixed default duration instead of the source
 * file's real per-frame timing, so animation speed may not exactly match the
 * original. If that's visibly wrong in practice, the real fix is parsing the
 * WebP container's ANMF chunk headers ourselves instead of going through
 * ImageIO metadata at all.
 */
public final class WebpFrameDecoder {

    private static final int DEFAULT_FRAME_DURATION_MS = 100;

    public record Result(List<byte[]> frames, int frameDurationMs, int width, int height) {}

    private WebpFrameDecoder() {}

    public static Result decode(byte[] webpBytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(webpBytes))) {
            if (input == null) throw new IOException("Could not open WebP input stream");

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
            if (!readers.hasNext()) throw new IOException("No WebP reader available");

            ImageReader reader = readers.next();
            reader.setInput(input);

            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
            } catch (IOException e) {
                numFrames = 1; // not every stream reliably reports a frame count up front
            }
            if (numFrames <= 0) numFrames = 1;

            List<byte[]> frames = new ArrayList<>(numFrames);
            int width = 0;
            int height = 0;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame;
                try {
                    frame = reader.read(i);
                } catch (IOException e) {
                    break; // stop at the last frame the reader actually has
                }
                if (frame == null) break;

                if (i == 0) {
                    width = frame.getWidth();
                    height = frame.getHeight();
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(frame, "png", out);
                frames.add(out.toByteArray());
            }

            reader.dispose();

            if (frames.isEmpty()) throw new IOException("WebP had no readable frames");
            return new Result(frames, DEFAULT_FRAME_DURATION_MS, width, height);
        }
    }
}