package com.skyfield.mcdiscordbridge.server.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class LinkManager {
    private static final Path LINK_FILE = Path.of("linked_users.json");
    private static final Map<UUID, String> pendingCodes = new HashMap<>();
    private static final List<LinkEntry> linkedUsers = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<LinkEntry>>() {}.getType();

    static {
        loadLinks();
    }

    public static String generateCode(ServerPlayer player) {
        UUID uuid = player.getUUID();

        if (isLinked(uuid)) {
            return "ALREADY_LINKED";
        }

        String code = UUID.randomUUID().toString().substring(0, 8);
        pendingCodes.put(uuid, code);
        return code;
    }

    public static boolean verifyCode(String discordUserId, String code) {
        UUID matchedUUID = null;

        for (Map.Entry<UUID, String> entry : pendingCodes.entrySet()) {
            if (entry.getValue().equals(code)) {
                matchedUUID = entry.getKey();
                break;
            }
        }

        if (matchedUUID != null) {
            pendingCodes.remove(matchedUUID);
            linkedUsers.add(new LinkEntry(matchedUUID.toString(), discordUserId, Instant.now().toString()));
            saveLinks();
            return true;
        }
        return false;
    }

    public static boolean isLinked(UUID uuid) {
        return linkedUsers.stream().anyMatch(link -> link.minecraftUuid.equals(uuid.toString()));
    }

    public static boolean isDiscordLinked(String discordId) {
        return linkedUsers.stream().anyMatch(link -> link.discordId.equals(discordId));
    }

    private static void saveLinks() {
        try (Writer writer = Files.newBufferedWriter(LINK_FILE)) {
            GSON.toJson(linkedUsers, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadLinks() {
        if (!Files.exists(LINK_FILE)) return;

        try (Reader reader = Files.newBufferedReader(LINK_FILE)) {
            List<LinkEntry> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded != null) {
                linkedUsers.clear();
                linkedUsers.addAll(loaded);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Inner class for structured data
    public static class LinkEntry {
        public String minecraftUuid;
        public String discordId;
        public String linkedAt;

        LinkEntry(String minecraftUuid, String discordId, String linkedAt) {
            this.minecraftUuid = minecraftUuid;
            this.discordId = discordId;
            this.linkedAt = linkedAt;
        }
    }
    public static List<LinkEntry> getAllLinks() {
        return Collections.unmodifiableList(linkedUsers);
    }
}
