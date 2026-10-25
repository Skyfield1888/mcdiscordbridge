package com.skyfield.mcdiscordbridge.server.discord;

import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import com.skyfield.mcdiscordbridge.server.emoji.EmojiKeys;
import com.skyfield.mcdiscordbridge.server.emoji.EmojiServerManager;
import com.skyfield.mcdiscordbridge.server.network.DiscordChatPayload;
import com.skyfield.mcdiscordbridge.server.voicechat.DiscordAudioReceiver;
import com.skyfield.mcdiscordbridge.server.voicechat.DiscordToMinecraftAudioSink;
import com.skyfield.mcdiscordbridge.server.voicechat.VoiceBridgeAudioProvider;
import com.skyfield.mcdiscordbridge.server.voicechat.VoiceBridgePlugin;
import de.maxhenkel.opus4j.OpusDecoder;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageUpdateEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.Embed;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.core.spec.AudioChannelJoinSpec;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import discord4j.voice.VoiceConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordBotManager {

    private final String token;
    private final String channelId;
    private final String voiceChannelId; // ID of the Discord voice channel to bridge

    private GatewayDiscordClient client;
    private MessageChannel targetChannel;
    private VoiceConnection voiceConnection;
    private VoiceBridgeAudioProvider audioProvider;

    // Maps Discord user ID → their AudioSender UUID in SVC (fake in-game player)
    private final Map<String, UUID> discordSenderMap = new ConcurrentHashMap<>();

    // Dedupe key: "<messageId>:<hash>" - stops a re-fired embedsChanged event (or an
    // edit that happens to touch embeds again) from re-announcing the same gif line.
    // Bounded LRU so long-running bots don't leak memory.
    private final Set<String> announcedEmbedGifs = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > 500;
                }
            })
    );

    public DiscordBotManager(String token, String channelId, String voiceChannelId) {
        this.token = token;
        this.channelId = channelId;
        this.voiceChannelId = voiceChannelId;
    }

    public void start() {
        com.skyfield.mcdiscordbridge.shaded.io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory(
                com.skyfield.mcdiscordbridge.shaded.io.netty.util.internal.logging.Slf4JLoggerFactory.INSTANCE
        );
        try {
            client = DiscordClientBuilder.create(token)
                    .build()
                    .gateway()
                    .setEnabledIntents(IntentSet.of(
                            Intent.GUILD_MESSAGES,
                            Intent.GUILD_MEMBERS,
                            Intent.GUILD_VOICE_STATES,
                            Intent.MESSAGE_CONTENT
                    ))
                    .login()
                    .block();

            if (client == null) {
                McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to log in to Discord!");
                return;
            }

            targetChannel = (MessageChannel) client
                    .getChannelById(Snowflake.of(channelId)).block();

            McDiscordBridge.LOGGER.info("[McDiscordBridge] Discord bot logged in.");

            // --- Register slash commands and message listeners (your existing code) ---
            setupMessageListeners();
            setupSlashCommands();

            // --- Join voice channel and set up audio bridge ---
            joinVoiceChannel();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void joinVoiceChannel() {
        DiscordToMinecraftAudioSink sink = VoiceBridgePlugin.INSTANCE.getDiscordSink();
        OpusDecoder decoder = VoiceBridgePlugin.INSTANCE.getDiscordDecoder();

        if (sink == null || decoder == null) {
            McDiscordBridge.LOGGER.warn("[McDiscordBridge] Voice chat not ready yet, retrying in 1s...");
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                joinVoiceChannel();
            }, "mcdiscordbridge-voice-retry").start();
            return;
        }

        try {
            VoiceChannel vc = (VoiceChannel) client
                    .getChannelById(Snowflake.of(voiceChannelId)).block();
            if (vc == null) {
                McDiscordBridge.LOGGER.error("[McDiscordBridge] Voice channel not found: " + voiceChannelId);
                return;
            }

            audioProvider = new VoiceBridgeAudioProvider();
            VoiceBridgePlugin.INSTANCE.setAudioProvider(audioProvider);

            vc.join(AudioChannelJoinSpec.builder()
                    .provider(audioProvider)
                    .receiver(new DiscordAudioReceiver(sink, decoder))
                    .selfDeaf(false)
                    .ipDiscoveryTimeout(Duration.ofSeconds(20))
                    .timeout(Duration.ofSeconds(60))
                    .build()
            ).subscribe(
                    connection -> {
                        this.voiceConnection = connection;
                        McDiscordBridge.LOGGER.info("[McDiscordBridge] Joined Discord voice channel successfully.");
                    },
                    error -> McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to join voice channel: " + error.getMessage())
            );

        } catch (Exception e) {
            McDiscordBridge.LOGGER.error("[McDiscordBridge] Failed to join voice channel: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void setupMessageListeners() {
        long applicationId = client.getRestClient().getApplicationId().block();

        client.on(MessageCreateEvent.class)
                .flatMap(event -> {
                    if (!event.getMessage().getChannelId().asString().equals(channelId))
                        return Mono.empty();
                    if (event.getMessage().getAuthor().isEmpty()
                            || event.getMessage().getAuthor().get().isBot())
                        return Mono.empty();

                    StringBuilder contentBuilder = new StringBuilder(event.getMessage().getContent());

                    String content = contentBuilder.toString();
                    EmojiServerManager.resolveAllIn(content);

                    // Discord stickers - append synthetic <s:name:id> tokens so the client
                    // renders them through the same inline pipeline as custom emoji.
                    var stickerItems = event.getMessage().getStickersItems();
                    if (stickerItems != null && !stickerItems.isEmpty()) {
                        java.util.List<EmojiKeys.StickerRef> staticStickers = new java.util.ArrayList<>();
                        java.util.List<EmojiKeys.StickerRef> animatedStickers = new java.util.ArrayList<>();
                        StringBuilder withStickers = new StringBuilder(content);

                        for (var sticker : stickerItems) {
                            String id = sticker.getId().asString();
                            String name = sticker.getName();
                            var format = sticker.getFormatType();

                            McDiscordBridge.LOGGER.info("[McDiscordBridge] Sticker {} format: {}", name, format); // TEMP - remove after checking

                            if (format == discord4j.core.object.entity.Sticker.Format.LOTTIE) {
                                McDiscordBridge.LOGGER.info("[McDiscordBridge] Skipping Lottie sticker {} - vector animation, no raster decoder", name);
                                continue;
                            }

                            boolean animated = format == discord4j.core.object.entity.Sticker.Format.UNKNOWN;

                            var ref = new EmojiKeys.StickerRef("sticker:" + id, id, name, 0, 0);
                            (animated ? animatedStickers : staticStickers).add(ref);
                            withStickers.append(' ').append(EmojiKeys.stickerToken(name, id));
                        }

                        if (!staticStickers.isEmpty()) EmojiServerManager.resolveStickersIn(staticStickers, false);
                        if (!animatedStickers.isEmpty()) EmojiServerManager.resolveStickersIn(animatedStickers, true);
                        content = withStickers.toString();
                    }

                    // Embeds already attached at message-create time (rare for GIF links -
                    // Discord usually populates these a moment later via MessageUpdateEvent,
                    // handled below - but some clients/webhooks send them immediately).
                    if (!event.getMessage().getEmbeds().isEmpty()) {
                        StringBuilder withGifs = new StringBuilder(content);
                        boolean resolvedAnyGif = false;
                        for (Embed embed : event.getMessage().getEmbeds()) {
                            String gifUrl = extractEmbedImageUrl(embed);
                            if (gifUrl == null) continue; // no image/video/thumbnail field - nothing to resolve

                            String hash = Integer.toHexString(gifUrl.hashCode());
                            EmojiServerManager.resolveEmbedGif("gif:" + hash, gifUrl);
                            resolvedAnyGif = true;

                            // Remove the raw pasted link text - the image itself now represents
                            // it, so showing both the image and a redundant [link] is just noise.
                            String embedSourceUrl = embed.getUrl().orElse(null);
                            if (embedSourceUrl != null) {
                                int idx = withGifs.indexOf(embedSourceUrl);
                                if (idx >= 0) withGifs.delete(idx, idx + embedSourceUrl.length());
                            }

                            withGifs.append(' ').append(EmojiKeys.gifEmbedToken(hash));
                            announcedEmbedGifs.add(event.getMessage().getId().asString());
                        }
                        String result = withGifs.toString();
                        if (resolvedAnyGif) result = stripGifLinks(result);
                        content = result.replaceAll(" {2,}", " ").trim();
                    }

                    // Attached image/gif files (dragged/uploaded, not links). These arrive
                    // fully formed on the create event - no update-event delay needed.
                    List<Attachment> attachments = event.getMessage().getAttachments();
                    if (!attachments.isEmpty()) {
                        StringBuilder withAttachments = new StringBuilder(content);
                        boolean resolvedAnyAttachmentGif = false;
                        for (Attachment attachment : attachments) {
                            if (!isImageAttachment(attachment)) continue;

                            String url = attachment.getUrl();
                            String hash = Integer.toHexString(url.hashCode());
                            EmojiServerManager.resolveEmbedGif("gif:" + hash, url);
                            resolvedAnyAttachmentGif = true;
                            withAttachments.append(' ').append(EmojiKeys.gifEmbedToken(hash));
                            announcedEmbedGifs.add(event.getMessage().getId().asString());
                        }
                        String result = withAttachments.toString();
                        if (resolvedAnyAttachmentGif) result = stripGifLinks(result);
                        content = result.replaceAll(" {2,}", " ").trim();
                    }

                    String author = resolveDisplayName(event.getMessage());

                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                            String processedMessage = content;
                            for (var mentionedUser : event.getMessage().getUserMentions()) {
                                String discordId = mentionedUser.getId().asString();
                                LinkManager.LinkEntry linked = LinkManager.getAllLinks().stream()
                                        .filter(link -> link.discordId.equals(discordId))
                                        .findFirst().orElse(null);
                                String displayName;
                                if (linked != null) {
                                    ServerPlayer mentionedPlayer = server.getPlayerList()
                                            .getPlayer(UUID.fromString(linked.minecraftUuid));
                                    displayName = mentionedPlayer != null
                                            ? mentionedPlayer.getName().getString() : "Unknown";
                                    if (mentionedPlayer != null && mentionedPlayer.isAlive())
                                        McDiscordBridge.playNotifySound(mentionedPlayer);
                                } else {
                                    displayName = mentionedUser.getGlobalName().orElse(mentionedUser.getUsername());
                                }
                                processedMessage = processedMessage.replace(
                                        "<@" + discordId + ">", "§9@" + displayName + " ");
                            }

                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                    player, new DiscordChatPayload(author, processedMessage));
                        }
                    }
                    return Mono.empty();
                }).subscribe();

        // Handles the common case where a message is *just* a GIF/Tenor/Giphy link:
        // Discord doesn't attach the embed at create time, it fires this event a moment
        // later once its own crawler has fetched the preview. Since Minecraft chat lines
        // can't be edited in place, we send a small follow-up line carrying just the gif.
        client.on(MessageUpdateEvent.class)
                .flatMap(event -> {
                    if (!event.getChannelId().asString().equals(channelId)) return Mono.empty();
                    if (!event.isEmbedsChanged()) return Mono.empty();

                    String messageId = event.getMessageId().asString();
                    if (announcedEmbedGifs.contains(messageId)) return Mono.empty(); // already showed a gif for this message

                    List<Embed> embeds = event.getCurrentEmbeds();
                    if (embeds == null || embeds.isEmpty()) return Mono.empty();

                    List<String> newHashes = new ArrayList<>();
                    for (Embed embed : embeds) {
                        String gifUrl = extractEmbedImageUrl(embed);
                        if (gifUrl == null) continue;

                        String hash = Integer.toHexString(gifUrl.hashCode());
                        EmojiServerManager.resolveEmbedGif("gif:" + hash, gifUrl);
                        newHashes.add(hash);
                    }
                    if (newHashes.isEmpty()) return Mono.empty();
                    announcedEmbedGifs.add(messageId);

                    // The update event itself doesn't carry the author - the message content
                    // (including author, since authors can't change) needs a fetch. Prefer the
                    // cached "old" copy when available to avoid a REST round-trip.
                    Mono<Message> messageMono = event.getOld()
                            .map(Mono::just)
                            .orElseGet(event::getMessage);

                    return messageMono
                            .doOnNext(message -> {
                                if (message.getAuthor().isEmpty() || message.getAuthor().get().isBot()) return;

                                String author = resolveDisplayName(message);
                                StringBuilder gifTokens = new StringBuilder();
                                for (String hash : newHashes) {
                                    if (gifTokens.length() > 0) gifTokens.append(' ');
                                    gifTokens.append(EmojiKeys.gifEmbedToken(hash));
                                }

                                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                                if (server == null) return;
                                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                            player, new DiscordChatPayload(author, gifTokens.toString()));
                                }
                            })
                            .onErrorResume(e -> {
                                McDiscordBridge.LOGGER.warn("[McDiscordBridge] Failed to resolve author for "
                                        + "delayed embed on message " + messageId + ": " + e.getMessage());
                                return Mono.empty();
                            })
                            .then();
                }).subscribe();
    }

    /** Same display-name resolution used for both fresh messages and delayed-embed
     *  follow-ups: prefer the guild nickname, then Discord's global display name,
     *  then the raw username. */
    private static String resolveDisplayName(Message message) {
        return message.getAuthor()
                .map(user -> message.getGuild()
                        .flatMap(guild -> guild.getMemberById(user.getId()))
                        .map(member -> member.getDisplayName())
                        .blockOptional()
                        .orElse(user.getGlobalName().orElse(user.getUsername())))
                .orElse("Unknown");
    }

    /** Discord "gifv" embeds - the norm for Tenor/Giphy/Klipy-style GIF links - carry
     *  their content in the video field as an mp4, not the image field. Our decoder
     *  can only handle GIF/PNG/JPEG frames, not video, so for those we fall back to
     *  the static thumbnail preview: you lose the animation, but at least something
     *  renders instead of a bare "[link]". Real .gif files/attachments still come
     *  through the image field directly and animate normally. */
    private static String extractEmbedImageUrl(Embed embed) {
        String imageUrl = embed.getImage().map(DiscordBotManager::preferProxy).orElse(null);
        if (imageUrl != null) return imageUrl;

        String videoUrl = embed.getVideo().map(Embed.Video::getUrl).orElse(null);
        if (videoUrl != null && videoUrl.toLowerCase(Locale.ROOT).endsWith(".gif")) {
            return videoUrl; // uncommon, but some providers do serve a real .gif here
        }

        return embed.getThumbnail().map(DiscordBotManager::preferProxy).orElse(null);
    }

    /** Discord's proxied CDN URL (media.discordapp.net) is fetched server-side and served
     *  without the source's own hotlink protection/rate limits. Some GIF sites (Klipy, etc.)
     *  return a 200 with an HTML error page instead of image bytes when hit directly, which
     *  ImageIO/GifFrameDecoder then fail to decode - going through Discord's proxy avoids that. */
    private static String preferProxy(Embed.Image image) {
        try {
            String proxy = image.getProxyUrl(); // throws ISE (not null) when absent
            if (proxy != null && !proxy.isBlank()) return proxy;
        } catch (IllegalStateException ignored) {
            // no proxy url on this embed - fall through to the source url below
        }
        return image.getUrl();
    }

    private static String preferProxy(Embed.Thumbnail thumbnail) {
        try {
            String proxy = thumbnail.getProxyUrl(); // throws ISE (not null) when absent
            if (proxy != null && !proxy.isBlank()) return proxy;
        } catch (IllegalStateException ignored) {
            // no proxy url on this embed - fall through to the source url below
        }
        return thumbnail.getUrl();
    }

    /** Blunt but reliable: strips any http(s) link containing "gif" anywhere in it
     *  (case-insensitive) - covers tenor.com/view/xxx-gif-123, klipy.com/gifs/..., plain
     *  *.gif files, etc. - without needing an exact string match against the embed's own
     *  url field, which can differ from what's actually in the message content. */
    private static String stripGifLinks(String text) {
        return text.replaceAll("(?i)https?://\\S*gif\\S*", "");
    }

    private static boolean isImageAttachment(Attachment attachment) {
        String contentType = attachment.getContentType().orElse("");
        if (contentType.startsWith("image/")) return true;

        String filename = attachment.getFilename().toLowerCase(Locale.ROOT);
        return filename.endsWith(".gif") || filename.endsWith(".png")
                || filename.endsWith(".jpg") || filename.endsWith(".jpeg")
                || filename.endsWith(".webp");
    }

    private void setupSlashCommands() {
        long applicationId = client.getRestClient().getApplicationId().block();

        ApplicationCommandRequest linkCmd = ApplicationCommandRequest.builder()
                .name("link")
                .description("Link your Discord account with Minecraft")
                .addOption(ApplicationCommandOptionData.builder()
                        .name("code")
                        .description("The link code from /mcdcbridge link in Minecraft")
                        .type(ApplicationCommandOption.Type.STRING.getValue())
                        .required(true)
                        .build())
                .build();

        client.getRestClient().getApplicationService()
                .createGlobalApplicationCommand(applicationId, linkCmd)
                .subscribe();

        client.on(ChatInputInteractionEvent.class, event -> {
            if (!event.getCommandName().equals("link")) return Mono.empty();
            String discordId = event.getInteraction().getUser().getId().asString();
            String code = event.getOption("code")
                    .flatMap(opt -> opt.getValue().map(v -> v.asString())).orElse("");
            if (LinkManager.isDiscordLinked(discordId))
                return event.reply("❌ You already linked a Minecraft account.").withEphemeral(true);
            boolean success = LinkManager.verifyCode(discordId, code);
            return success
                    ? event.reply("✅ Successfully linked!")
                    : event.reply("❌ Invalid or expired code.").withEphemeral(true);
        }).subscribe();
    }

    public void sendMessageToDiscord(String content) {
        if (targetChannel == null || client == null) return;

        targetChannel.createMessage(content)
                .subscribe(
                        message -> { /* sent successfully, nothing else to do */ },
                        error -> McDiscordBridge.LOGGER.error(
                                "[McDiscordBridge] Failed to send message to Discord: " + error.getMessage())
                );
    }

    public void shutdown() {
        if (voiceConnection != null) {
            voiceConnection.disconnect().block();
            voiceConnection = null;
        }
        if (client != null) {
            client.logout().block();
            client = null;
        }
    }
}