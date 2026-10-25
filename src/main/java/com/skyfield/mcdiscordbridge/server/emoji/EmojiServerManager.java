package com.skyfield.mcdiscordbridge.server.emoji;

import com.google.gson.Gson;
import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import com.skyfield.mcdiscordbridge.server.network.AnimatedEmojiAssetPacket;
import com.skyfield.mcdiscordbridge.server.network.EmojiAssetPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class EmojiServerManager {

    private static final Path CACHE_DIR = Path.of("config/mcdiscordbridge/emoji_cache");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();

    private record Manifest(boolean animated, int frameDuration, int frameCount) {}

    private EmojiServerManager() {}

    public static void init() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Could not create emoji cache dir", e);
        }
    }

    /** Scans a relayed message for custom emoji + Twemoji clusters and
     *  resolves anything not already cached before the chat line is forwarded. */
    public static void resolveAllIn(String content) {
        Set<CompletableFuture<Void>> futures = new HashSet<>();

        for (EmojiKeys.CustomEmojiRef ref : EmojiKeys.findCustomEmoji(content)) {
            if (isCached(ref.key())) continue;
            if (ref.animated()) {
                String url = "https://cdn.discordapp.com/emojis/" + ref.id() + ".gif";
                futures.add(resolveAnimatedAndBroadcast(ref.key(), url));
            } else {
                String url = "https://cdn.discordapp.com/emojis/" + ref.id() + ".png";
                futures.add(resolveStaticAndBroadcast(ref.key(), url, null));
            }
        }

        for (String key : EmojiKeys.findTwemojiKeys(content)) {
            if (isCached(key)) continue;
            String hex = key.substring("twemoji:".length());
            String primary = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/"
                    + EmojiKeys.stripFe0f(hex) + ".png";
            String fallback = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/" + hex + ".png";
            futures.add(resolveStaticAndBroadcast(key, primary, fallback));
        }

        awaitAll(futures);
    }

    /** Resolves any Discord stickers attached to a message. Static (PNG) and
     *  GIF-format stickers get full support; APNG-format stickers fall back
     *  to their first frame only, since plain ImageIO has no APNG decoder. */
    public static void resolveStickersIn(List<EmojiKeys.StickerRef> stickers, boolean animated) {
        Set<CompletableFuture<Void>> futures = new HashSet<>();
        for (EmojiKeys.StickerRef ref : stickers) {
            if (isCached(ref.key())) continue;
            if (animated) {
                String url = "https://cdn.discordapp.com/stickers/" + ref.id() + ".gif";
                futures.add(resolveAnimatedAndBroadcast(ref.key(), url));
            } else {
                String url = "https://cdn.discordapp.com/stickers/" + ref.id() + ".png";
                futures.add(resolveStaticAndBroadcast(ref.key(), url, null));
            }
        }
        awaitAll(futures);
    }

    private static void awaitAll(Set<CompletableFuture<Void>> futures) {
        if (futures.isEmpty()) return;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            McDiscordBridge.LOGGER.warn("[McDiscordBridge] Emoji/sticker resolve timed out, "
                    + "forwarding chat anyway: " + e.getMessage());
        }
    }

    private static CompletableFuture<Void> resolveStaticAndBroadcast(String key, String primaryUrl, String fallbackUrl) {
        return inflight.computeIfAbsent(key, k -> CompletableFuture.runAsync(() -> {
            try {
                byte[] png = downloadAndNormalizeStatic(primaryUrl, fallbackUrl, key);
                if (png == null) {
                    McDiscordBridge.LOGGER.warn("[McDiscordBridge] No texture found for {}", key);
                    return;
                }
                Files.write(staticPath(key), png, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                writeManifest(key, new Manifest(false, 0, 1));
                broadcastToAll(new EmojiAssetPacket(key, png));
            } catch (Exception e) {
                McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to resolve " + key, e);
            } finally {
                inflight.remove(key);
            }
        }, POOL));
    }

    private static CompletableFuture<Void> resolveAnimatedAndBroadcast(String key, String url) {
        return inflight.computeIfAbsent(key, k -> CompletableFuture.runAsync(() -> {
            try {
                byte[] gifBytes = tryDownload(url);
                if (gifBytes == null) {
                    McDiscordBridge.LOGGER.warn("[McDiscordBridge] No animated texture found for {} (url: {})", key, url);
                    return;
                }

                GifFrameDecoder.Result decoded;
                try {
                    decoded = GifFrameDecoder.decode(gifBytes);
                } catch (IOException notAGif) {
                    // Try animated WebP next (klipy/tenor-style thumbnails often come back
                    // as WebP once routed through Discord's media proxy). Falls through to
                    // the generic single-frame ImageIO.read below if this isn't WebP either,
                    // or if it's WebP but only has one frame.
                    try {
                        WebpFrameDecoder.Result webp = WebpFrameDecoder.decode(gifBytes);
                        McDiscordBridge.LOGGER.info("[McDiscordBridge] Decoded {} as WebP: {} frame(s), {}x{}",
                                key, webp.frames().size(), webp.width(), webp.height());
                        if (webp.frames().size() > 1) {
                            for (int i = 0; i < webp.frames().size(); i++) {
                                Files.write(framePath(key, i), webp.frames().get(i),
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            }
                            writeManifest(key, new Manifest(true, webp.frameDurationMs(), webp.frames().size()));
                            broadcastToAll(new AnimatedEmojiAssetPacket(key, webp.frames(), webp.frameDurationMs()));
                            return;
                        } else if (!webp.frames().isEmpty()) {
                            byte[] png = webp.frames().get(0);
                            Files.write(staticPath(key), png, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            writeManifest(key, new Manifest(false, 0, 1));
                            broadcastToAll(new EmojiAssetPacket(key, png));
                            return;
                        }
                    } catch (IOException notWebpEither) {
                        McDiscordBridge.LOGGER.info(
                                "[McDiscordBridge] {} isn't decodable as WebP either ({}), falling back to plain ImageIO.read",
                                key, notWebpEither.getMessage());
                    }

                    // Some "animated" stickers are actually APNG served with a
                    // misleading extension, or GIF/WebP decode failed outright -
                    // fall back to treating it as a single static image.
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(gifBytes));
                    if (img == null) {
                        McDiscordBridge.LOGGER.warn(
                                "[McDiscordBridge] Could not decode {} as GIF or static image. url={} bytes={} header={}",
                                key, url, gifBytes.length, hexPreview(gifBytes));
                        return;
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", out);
                    byte[] png = out.toByteArray();
                    Files.write(staticPath(key), png, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    writeManifest(key, new Manifest(false, 0, 1));
                    broadcastToAll(new EmojiAssetPacket(key, png));
                    return;
                }

                for (int i = 0; i < decoded.frames().size(); i++) {
                    Files.write(framePath(key, i), decoded.frames().get(i),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                writeManifest(key, new Manifest(true, decoded.frameDurationMs(), decoded.frames().size()));
                broadcastToAll(new AnimatedEmojiAssetPacket(key, decoded.frames(), decoded.frameDurationMs()));
            } catch (Exception e) {
                McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to resolve animated " + key + " (url: " + url + ")", e);
            } finally {
                inflight.remove(key);
            }
        }, POOL));
    }

    /** First 16 bytes of the response as hex, plus a guess at the format from its magic
     *  bytes. Lets us tell "got HTML instead of an image" (starts with "3c68746d6c" / "<html")
     *  apart from "got a format ImageIO can't read" (e.g. "RIFF....WEBP") without needing to
     *  reproduce the actual asset. */
    private static String hexPreview(byte[] bytes) {
        int len = Math.min(bytes.length, 16);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < len; i++) {
            hex.append(String.format("%02X", bytes[i]));
        }

        String guess;
        String ascii = new String(bytes, 0, Math.min(bytes.length, 32), java.nio.charset.StandardCharsets.US_ASCII);
        if (ascii.startsWith("<") || ascii.contains("<html") || ascii.contains("<!DOCTYPE")) {
            guess = "HTML (likely an error/redirect page, not an image)";
        } else if (bytes.length >= 12 && ascii.startsWith("RIFF") && ascii.substring(8, 12).equals("WEBP")) {
            guess = "WebP (ImageIO has no built-in reader for this without an extra plugin)";
        } else if (ascii.startsWith("{") || ascii.startsWith("[")) {
            guess = "JSON (likely an API error response, not an image)";
        } else {
            guess = "unknown";
        }

        return hex + " [" + guess + "]";
    }

    /** Push every already-cached emoji/sticker to one player - call on join. */
    public static void sendKnownEmojiTo(ServerPlayer player) {
        if (!Files.isDirectory(CACHE_DIR)) return;
        try (var stream = Files.list(CACHE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(manifestPath -> {
                try {
                    String fileName = manifestPath.getFileName().toString();
                    String sanitizedKey = fileName.substring(0, fileName.length() - ".json".length());
                    sendOne(player, sanitizedKey, manifestPath);
                } catch (IOException e) {
                    McDiscordBridge.LOGGER.error("[McDiscordBridge] Could not resend cached emoji "
                            + manifestPath, e);
                }
            });
        } catch (IOException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Could not list emoji cache dir", e);
        }
    }

    public static void handleMissingRequest(ServerPlayer player, String key) {
        Path manifestPath = manifestPath(key);
        if (!Files.exists(manifestPath)) return; // never seen this key in a relayed message
        try {
            sendOne(player, EmojiKeys.sanitize(key), manifestPath);
        } catch (IOException e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Could not resend emoji " + key, e);
        }
    }

    private static void sendOne(ServerPlayer player, String sanitizedKey, Path manifestPath) throws IOException {
        Manifest manifest = readManifest(manifestPath);
        if (manifest == null) return;

        if (!manifest.animated()) {
            Path pngPath = CACHE_DIR.resolve(sanitizedKey + ".png");
            if (!Files.exists(pngPath)) return;
            byte[] png = Files.readAllBytes(pngPath);
            PacketDistributor.sendToPlayer(player, new EmojiAssetPacket(sanitizedKey, png));
        } else {
            List<byte[]> frames = new java.util.ArrayList<>(manifest.frameCount());
            for (int i = 0; i < manifest.frameCount(); i++) {
                Path framePath = CACHE_DIR.resolve(sanitizedKey + "_frame_" + i + ".png");
                if (!Files.exists(framePath)) return; // incomplete cache - skip rather than send partial
                frames.add(Files.readAllBytes(framePath));
            }
            PacketDistributor.sendToPlayer(player,
                    new AnimatedEmojiAssetPacket(sanitizedKey, frames, manifest.frameDuration()));
        }
    }

    private static void broadcastToAll(EmojiAssetPacket packet) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static void broadcastToAll(AnimatedEmojiAssetPacket packet) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static byte[] downloadAndNormalizeStatic(String primaryUrl, String fallbackUrl, String key) throws IOException {
        byte[] raw = tryDownload(primaryUrl);
        if (raw == null && fallbackUrl != null) raw = tryDownload(fallbackUrl);
        if (raw == null) return null;

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) {
            McDiscordBridge.LOGGER.warn(
                    "[McDiscordBridge] Could not decode static image for {}. url={} bytes={} header={}",
                    key, primaryUrl, raw.length, hexPreview(raw));
            throw new IOException("Could not decode image for " + key);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] tryDownload(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isCached(String key) {
        return Files.exists(manifestPath(key));
    }

    private static Path staticPath(String key) {
        return CACHE_DIR.resolve(EmojiKeys.sanitize(key) + ".png");
    }

    private static Path framePath(String key, int index) {
        return CACHE_DIR.resolve(EmojiKeys.sanitize(key) + "_frame_" + index + ".png");
    }

    private static Path manifestPath(String key) {
        return CACHE_DIR.resolve(EmojiKeys.sanitize(key) + ".json");
    }

    private static void writeManifest(String key, Manifest manifest) throws IOException {
        try (Writer w = Files.newBufferedWriter(manifestPath(key))) {
            GSON.toJson(manifest, w);
        }
    }

    private static Manifest readManifest(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            return GSON.fromJson(r, Manifest.class);
        }
    }
    public static void resolveEmbedGif(String key, String url) {
        if (isCached(key)) return;
        CompletableFuture<Void> future = resolveAnimatedAndBroadcast(key, url);
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            McDiscordBridge.LOGGER.warn("[McDiscordBridge] Embed gif resolve timed out for {}: {}", key, e.getMessage());
        }
    }
}