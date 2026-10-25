package com.skyfield.mcdiscordbridge.server.emoji;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes an animated GIF into a sequence of fully-composited PNG frames,
 * honoring GIF disposal methods the same way the reference discord-chat-mod
 * does. A naive frame-by-frame ImageIO read without this compositing step
 * produces garbled/ghosted output for most real-world animated Discord
 * emoji and stickers, since GIF frames are deltas against a persistent
 * canvas, not independent images.
 */
public final class GifFrameDecoder {

    public record Result(List<byte[]> frames, int frameDurationMs, int width, int height) {}

    private GifFrameDecoder() {}

    public static Result decode(byte[] gifBytes) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        int frameDuration = -1;
        int outWidth;
        int outHeight;

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF reader available");

            ImageReader reader = readers.next();
            reader.setInput(input);
            int numFrames = reader.getNumImages(true);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            outWidth = width;
            outHeight = height;

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = canvas.createGraphics();
            g2d.setBackground(new Color(0, 0, 0, 0));
            g2d.clearRect(0, 0, width, height);

            BufferedImage savedCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);
                IIOMetadata metadata = reader.getImageMetadata(i);
                Rectangle frameRect = getFrameRect(metadata);
                String disposal = getDisposalMethod(metadata);

                switch (disposal) {
                    case "restoreToPrevious" -> savedCanvas = deepCopy(canvas);
                    case "restoreToBackgroundColor" -> clearArea(canvas, frameRect);
                    default -> { /* none / doNotDispose - leave canvas as-is */ }
                }

                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, frameRect.x, frameRect.y, null);
                g.dispose();

                if ("restoreToPrevious".equals(disposal) && savedCanvas != null) {
                    Graphics2D gRestore = canvas.createGraphics();
                    gRestore.drawImage(savedCanvas, 0, 0, null);
                    gRestore.dispose();
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(canvas, "png", out);
                frames.add(out.toByteArray());

                if (i == 0) {
                    frameDuration = getFrameDuration(metadata);
                }
            }

            g2d.dispose();
            reader.dispose();
        }

        if (frames.isEmpty()) throw new IOException("GIF had no frames");
        return new Result(frames, Math.max(frameDuration, 100), outWidth, outHeight);
    }

    private static String getDisposalMethod(IIOMetadata metadata) {
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
        IIOMetadataNode gce = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
        return gce.getAttribute("disposalMethod");
    }

    private static Rectangle getFrameRect(IIOMetadata metadata) {
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
        IIOMetadataNode imgDesc = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
        int x = Integer.parseInt(imgDesc.getAttribute("imageLeftPosition"));
        int y = Integer.parseInt(imgDesc.getAttribute("imageTopPosition"));
        int w = Integer.parseInt(imgDesc.getAttribute("imageWidth"));
        int h = Integer.parseInt(imgDesc.getAttribute("imageHeight"));
        return new Rectangle(x, y, w, h);
    }

    private static int getFrameDuration(IIOMetadata metadata) {
        String metaFormat = metadata.getNativeMetadataFormatName();
        if (!"javax_imageio_gif_image_1.0".equals(metaFormat)) return 100;

        var root = metadata.getAsTree(metaFormat);
        var children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if ("GraphicControlExtension".equals(node.getNodeName())) {
                var delayNode = node.getAttributes().getNamedItem("delayTime");
                if (delayNode != null) return Integer.parseInt(delayNode.getNodeValue()) * 10;
            }
        }
        return 100;
    }

    private static void clearArea(BufferedImage canvas, Rectangle rect) {
        Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(rect.x, rect.y, rect.width, rect.height);
        g.dispose();
    }

    private static BufferedImage deepCopy(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        boolean alphaPremultiplied = cm.isAlphaPremultiplied();
        WritableRaster raster = bi.copyData(null);
        return new BufferedImage(cm, raster, alphaPremultiplied, null);
    }
}