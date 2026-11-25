package com.thenathe.litelights.seedhelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.render.feature.farterrain.seed.GenerationFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LiteLights on a dedicated server: the seed helper, and nothing else. LiteLights is a client
 * mod; put in a dedicated server's mods folder, this is all of it that runs there (its client
 * entrypoint, mixins and modules never load on a server). A player whose LiteLights asks is sent
 * the world's seed, so their far terrain can predict land they haven't visited, and the server's
 * world-generation fingerprint, so their game can say which mods or data packs differ from its own.
 * The helper generates nothing, stores nothing and does nothing for players who don't ask
 * (vanilla clients, clients without LiteLights).
 *
 * <p><b>Who gets the seed</b> is the admin's choice, in {@code config/litelights-seed-helper.json}:
 * {@code "share"} is {@code everyone} (the default: installing the helper is the choice to
 * share it), {@code operators}, {@code players} (only those named in {@code "players"}, by name or
 * UUID) or {@code off}; {@code "allowOfflineMode"} (false by default) lets an offline-mode server,
 * whose connections aren't encrypted, send it at all. A seed lets players work out where
 * structures and ores are; the server's documentation says so. The seed is never written to the
 * log, here or on the client.
 */
public final class SeedHelperServer implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("LiteLights seed helper");
    /** Requests a player may make in a minute; the client asks once a connection. */
    private static final int REQUESTS_PER_MINUTE = 4;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    enum Share { EVERYONE, OPERATORS, PLAYERS, OFF }

    record Settings(Share share, Set<String> players, boolean allowOfflineMode) {
    }

    private volatile Settings settings = new Settings(Share.EVERYONE, Set.of(), false);
    private volatile CompletableFuture<Map<String, String>> fingerprint = new CompletableFuture<>();
    private final Map<UUID, Deque<Long>> requests = new HashMap<>();

    @Override
    public void onInitializeServer() {
        SeedPayloads.register();
        settings = readSettings(FabricLoader.getInstance().getConfigDir().resolve("litelights-seed-helper.json"));
        ServerLifecycleEvents.SERVER_STARTED.register(this::started);
        ServerPlayNetworking.registerGlobalReceiver(SeedPayloads.Request.TYPE, (request, context) -> answer(request, context.player(),
            context.server()));
        LOG.info("LiteLights is on this server as its seed helper only (sharing: {}{})", settings.share().name().toLowerCase(Locale.ROOT),
            settings.allowOfflineMode() ? ", offline mode allowed" : "");
    }

    /** Works out the fingerprint once the world is loaded, off the server thread. */
    private void started(MinecraftServer server) {
        CompletableFuture<Map<String, String>> made = new CompletableFuture<>();
        fingerprint = made;
        Thread thread = new Thread(() -> {
            try {
                made.complete(GenerationFingerprint.of(server.registryAccess(), server.getPackRepository().getSelectedIds()).entries());
            } catch (RuntimeException failure) {
                LOG.warn("Couldn't work out the world-generation fingerprint; players are told it is unknown: {}", failure.toString());
                made.complete(Map.of("format", Integer.toString(GenerationFingerprint.FORMAT), "fingerprint", GenerationFingerprint.UNKNOWN));
            }
        }, "LiteLights seed helper fingerprint");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
    }

    private void answer(SeedPayloads.Request request, ServerPlayer player, MinecraftServer server) {
        String refusal = refusal(request, player, server);
        if (!refusal.isEmpty()) {
            ServerPlayNetworking.send(player, new SeedPayloads.Reply(SeedPayloads.PROTOCOL, refusal, 0L, Map.of()));
            return;
        }
        long seed = server.overworld().getSeed();
        fingerprint.thenAccept(entries -> server.execute(() -> {
            if (!player.hasDisconnected()) {
                ServerPlayNetworking.send(player, new SeedPayloads.Reply(SeedPayloads.PROTOCOL, "", seed, entries));
            }
        }));
    }

    /** Why this player isn't sent the seed, or "" when they are. */
    private String refusal(SeedPayloads.Request request, ServerPlayer player, MinecraftServer server) {
        if (request.protocol() < SeedPayloads.PROTOCOL) {
            return "protocol";
        }
        if (tooOften(player.getUUID())) {
            return "too_often";
        }
        Settings current = settings;
        if (current.share() == Share.OFF) {
            return "off";
        }
        if (!server.usesAuthentication() && !current.allowOfflineMode()) {
            return "offline_mode";
        }
        return switch (current.share()) {
            case EVERYONE -> "";
            case OPERATORS -> server.getPlayerList().isOp(player.nameAndId()) ? "" : "not_allowed";
            case PLAYERS -> current.players().contains(player.getPlainTextName().toLowerCase(Locale.ROOT))
                || current.players().contains(player.getUUID().toString()) ? "" : "not_allowed";
            case OFF -> "off";
        };
    }

    private synchronized boolean tooOften(UUID player) {
        long now = System.nanoTime();
        Deque<Long> times = requests.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000_000_000L) {
            times.removeFirst();
        }
        if (times.size() >= REQUESTS_PER_MINUTE) {
            return true;
        }
        times.addLast(now);
        return false;
    }

    /** Reads the settings, writing the defaults with their explanation when there is no file yet. */
    static Settings readSettings(Path file) {
        Settings defaults = new Settings(Share.EVERYONE, Set.of(), false);
        if (!Files.exists(file)) {
            JsonObject root = new JsonObject();
            root.addProperty("_about", "LiteLights' seed helper sends this world's seed to players whose LiteLights asks, so their far "
                + "terrain can show land they haven't visited. A seed lets players work out where structures and ores are. share: "
                + "everyone, operators, players (only those listed below, by name or UUID) or off. allowOfflineMode: send it from an "
                + "offline-mode server, whose connections aren't encrypted.");
            root.addProperty("share", "everyone");
            root.add("players", new JsonArray());
            root.addProperty("allowOfflineMode", false);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, JSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            } catch (IOException failure) {
                LOG.warn("Couldn't write {}: {}", file, failure.toString());
            }
            return defaults;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Share share = Share.valueOf(root.get("share").getAsString().trim().toUpperCase(Locale.ROOT));
            Set<String> players = new HashSet<>();
            if (root.has("players")) {
                root.getAsJsonArray("players").forEach(player -> players.add(player.getAsString().trim().toLowerCase(Locale.ROOT)));
            }
            boolean offline = root.has("allowOfflineMode") && root.get("allowOfflineMode").getAsBoolean();
            return new Settings(share, Set.copyOf(players), offline);
        } catch (IOException | RuntimeException unreadable) {
            // A file the admin wrote wrong shares nothing until it is put right.
            LOG.warn("Couldn't read {} ({}); the seed is not shared until it is fixed", file, unreadable.toString());
            return new Settings(Share.OFF, Set.of(), false);
        }
    }
}
