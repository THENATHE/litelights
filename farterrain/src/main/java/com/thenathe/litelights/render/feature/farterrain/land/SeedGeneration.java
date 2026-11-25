package com.thenathe.litelights.render.feature.farterrain.land;

import com.thenathe.litelights.api.CameraView;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.WorldIdentity;
import com.thenathe.litelights.render.feature.farterrain.seed.GenerationFingerprint;
import com.thenathe.litelights.render.feature.farterrain.seed.InstalledGenerationData;
import com.thenathe.litelights.render.feature.farterrain.seed.PredictionInputs;
import com.thenathe.litelights.render.feature.farterrain.seed.SeedEntryScreen;
import com.thenathe.litelights.render.feature.farterrain.seed.SeedNoticeScreen;
import com.thenathe.litelights.render.feature.farterrain.seed.ServerSeeds;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Seed generation's coordinator (docs/FAR-TERRAIN.md, "Seed generation"): where the seed and the
 * world's generation come from, whether they can be trusted to match the server, the notice when
 * they may not, and starting and stopping predictions in the open far terrain session.
 *
 * <ul>
 *   <li><b>Single player and the LAN host:</b> the integrated server's own generator, random
 *   state and seed, taken on the server thread: no notice, nothing to check.</li>
 *   <li><b>A server with LiteLights' seed helper:</b> the seed and the server's world-generation
 *   fingerprint from the helper; no notice when the fingerprints agree, else one naming what differs.</li>
 *   <li><b>Another server:</b> a seed the player typed on the Far Terrain page, or the game's own
 *   {@code /seed} reply (its structured message, within 30 seconds of the player running it),
 *   kept per server. The client's own data is generated with; the notice says the server's mods
 *   can't be checked, whether the seed is the world's (against the seed hash every server sends),
 *   and which biomes the server has that this game hasn't.</li>
 * </ul>
 * The notice never blocks: Generate, Not now, or Don't ask again for this server.
 */
final class SeedGeneration {
    /** Raised whenever the sampler's output changes, so predictions made before are made again. */
    static final int SAMPLER_VERSION = 1;
    private static final long CAPTURE_NANOS = 30_000_000_000L;

    enum Phase { OFF, WAITING_FOR_HELPER, WAITING_FOR_SEED, PREPARING, ASKING, DECLINED, RUNNING, UNSUPPORTED, MISMATCH, FAILED }

    /** What predictions start from, worked out away from the client thread. */
    private record Prepared(@Nullable PredictionInputs inputs, @Nullable String identity, @Nullable List<Component> notice,
                            @Nullable String unsupported) {
    }

    private final FarLandSettings settings;
    private @Nullable ServerSeeds seeds;
    private @Nullable ThreadPoolExecutor loader;
    private @Nullable FarLandSession session;
    private Phase phase = Phase.OFF;
    private @Nullable CompletableFuture<Prepared> preparing;
    private @Nullable String problem;
    /** The connection the player answered the notice for, and the answer: Generate or Not now hold until they leave. */
    private @Nullable Object answeredConnection;
    private boolean answeredGenerate;
    private SeedNoticeScreen.@Nullable Answer pendingAnswer;
    private @Nullable List<Component> pendingNotice;
    private @Nullable Prepared ready;
    private @Nullable Object installedDataConnection;
    private @Nullable CompletableFuture<InstalledGenerationData> installedData;
    private long captureUntil;
    private boolean mismatchSaid;
    private SeedHelperClient.@Nullable Reply helperReply;
    private @Nullable Object helperConnection;
    private long helperWaitUntil;
    private @Nullable Map<String, Object> mismatchReading;

    SeedGeneration(FarLandSettings settings) {
        this.settings = settings;
    }

    Phase phase() {
        return phase;
    }

    // --- Each frame, with far terrain's session open ---

    void frame(FarLandSession open, ClientLevel level, CameraView camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!settings.seedGeneration.on()) {
            if (phase != Phase.OFF) {
                off();
            }
            return;
        }
        if (open != session) {
            // A new world or dimension: whatever was prepared was for the last one.
            reset();
            session = open;
        }
        if (phase == Phase.WAITING_FOR_HELPER && System.nanoTime() - helperWaitUntil > 0) {
            // No reply in time: go on with a seed the player gave, if any.
            phase = Phase.OFF;
        }
        if (phase == Phase.OFF) {
            begin(open.world(), level, minecraft);
        }
        if (phase == Phase.PREPARING && preparing != null && preparing.isDone()) {
            finishPreparing(minecraft);
        }
        if (phase == Phase.ASKING) {
            ask(minecraft);
        }
        if (phase == Phase.RUNNING) {
            PredictedLand predicted = open.predictions();
            if (predicted != null && !predicted.matches()) {
                mismatchReading = predicted.accuracy();
                open.stopPredictions();
                phase = Phase.MISMATCH;
                if (!mismatchSaid) {
                    mismatchSaid = true;
                    say(minecraft, Component.translatable("litelights.seed_generation.mismatch_chat"));
                }
                return;
            }
            open.planPredictions(camera, settings, serverTickMillis(minecraft));
        }
    }

    private void begin(WorldIdentity world, ClientLevel level, Minecraft minecraft) {
        problem = null;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            String dimension = world.dimension();
            preparing = server.submit(() -> fromServer(server, dimension))
                .thenApplyAsync(inputs -> inputs == null ? new Prepared(null, null, null, "noise")
                    : new Prepared(inputs, identity(inputs, GenerationFingerprint.of(inputs.registries(), server.getPackRepository().getSelectedIds())),
                        null, null), loader());
            phase = Phase.PREPARING;
            return;
        }
        if (helperReply() == null && SeedHelperClient.ask(world.dimension())) {
            helperWaitUntil = System.nanoTime() + SeedHelperClient.WAIT_NANOS;
            phase = Phase.WAITING_FOR_HELPER;
            return;
        }
        OptionalLong seed = serverSeed(minecraft);
        if (seed.isEmpty()) {
            phase = Phase.WAITING_FOR_SEED;
            return;
        }
        long chosen = seed.getAsLong();
        String dimension = world.dimension();
        long hashed = seedHash(level);
        TreeSet<String> serverBiomes = new TreeSet<>();
        level.registryAccess().lookupOrThrow(Registries.BIOME).listElementIds().forEach(key -> serverBiomes.add(key.identifier().toString()));
        SeedHelperClient.Reply helper = helperReply();
        preparing = installedData(minecraft).thenApplyAsync(data -> {
            Optional<PredictionInputs> made = data.inputsFor(dimension, chosen);
            if (made.isEmpty()) {
                return new Prepared(null, null, null, "noise");
            }
            PredictionInputs inputs = made.get();
            GenerationFingerprint fingerprint = GenerationFingerprint.of(inputs.registries(), data.packs());
            List<Component> notice = notice(inputs, fingerprint, chosen, hashed, serverBiomes, helper);
            return new Prepared(inputs, identity(inputs, fingerprint), notice, null);
        }, loader());
        phase = Phase.PREPARING;
    }

    private void finishPreparing(Minecraft minecraft) {
        CompletableFuture<Prepared> done = preparing;
        preparing = null;
        Prepared prepared;
        try {
            prepared = done.join();
        } catch (RuntimeException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            problem = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            LiteLightsMod.LOG.error("Seed generation couldn't get ready", cause);
            phase = Phase.FAILED;
            return;
        }
        if (prepared.inputs() == null) {
            phase = Phase.UNSUPPORTED;
            return;
        }
        ready = prepared;
        if (prepared.notice() == null || alwaysForThisServer(minecraft)
            || (answeredConnection == minecraft.getConnection() && answeredGenerate)) {
            start(minecraft);
        } else if (answeredConnection == minecraft.getConnection() && !answeredGenerate) {
            phase = Phase.DECLINED;
        } else {
            pendingNotice = prepared.notice();
            phase = Phase.ASKING;
        }
    }

    private void start(Minecraft minecraft) {
        Prepared prepared = ready;
        FarLandSession open = session;
        if (prepared == null || open == null || minecraft.level == null) {
            return;
        }
        open.startPredictions(prepared.inputs(), prepared.identity(), minecraft.level);
        phase = Phase.RUNNING;
        // Never the seed: where it came from and what predictions are kept under.
        LiteLightsMod.LOG.info("LiteLights: seed generation predicting {} from {}, kept as {}", prepared.inputs().dimension(),
            prepared.inputs().fromWorld() ? "the world's own generator" : "this game's own world-generation data", prepared.identity());
    }

    /** Shows the notice once nothing else is on screen, and acts on the answer when it comes. */
    private void ask(Minecraft minecraft) {
        SeedNoticeScreen.Answer answer = pendingAnswer;
        if (answer != null) {
            pendingAnswer = null;
            answeredConnection = minecraft.getConnection();
            answeredGenerate = answer != SeedNoticeScreen.Answer.NOT_NOW;
            if (answer == SeedNoticeScreen.Answer.ALWAYS && address(minecraft) != null) {
                seeds().setChoice(address(minecraft), ServerSeeds.Choice.ALWAYS);
            }
            if (answeredGenerate) {
                start(minecraft);
            } else {
                phase = Phase.DECLINED;
            }
            return;
        }
        List<Component> notice = pendingNotice;
        if (notice != null && minecraft.gui.screen() == null && minecraft.player != null) {
            pendingNotice = null;
            minecraft.gui.setScreen(new SeedNoticeScreen(null, notice, chosen -> pendingAnswer = chosen));
        }
    }

    /** Seed generation switched off: predictions stop and stop showing; what was visited stays. */
    void off() {
        FarLandSession open = session;
        if (open != null) {
            open.stopPredictions();
        }
        reset();
    }

    /** The session closed (a new world, dimension, or far terrain off). */
    void sessionClosed() {
        reset();
        session = null;
    }

    private void reset() {
        phase = Phase.OFF;
        preparing = null;
        ready = null;
        pendingNotice = null;
        pendingAnswer = null;
        mismatchSaid = false;
    }

    /** Something about the seed changed (typed, captured, the helper replied): prepare again. */
    void seedChanged() {
        FarLandSession open = session;
        if (open != null) {
            open.stopPredictions();
        }
        answeredConnection = null;
        reset();
    }

    /** Deletes every prediction for this world and dimension and starts again. */
    void clear() {
        FarLandSession open = session;
        if (open != null) {
            open.clearPredictions();
        }
        reset();
    }

    // --- Where things come from ---

    /** On the server thread: the dimension's own generator and random state, if made by the noise generator. */
    private static @Nullable PredictionInputs fromServer(IntegratedServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        ServerLevel serverLevel = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (serverLevel == null) {
            return null;
        }
        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            return null;
        }
        return new PredictionInputs(serverLevel.getSeed(), dimension, noise, serverLevel.getChunkSource().randomState(), server.registryAccess(),
            true);
    }

    /** This client's own world generation, loaded once a connection, off the client thread. */
    private CompletableFuture<InstalledGenerationData> installedData(Minecraft minecraft) {
        Object connection = minecraft.getConnection();
        if (installedData == null || installedDataConnection != connection) {
            installedDataConnection = connection;
            Path datapacks = InstalledGenerationData.datapackFolder(FabricLoader.getInstance().getGameDir());
            installedData = CompletableFuture.supplyAsync(() -> InstalledGenerationData.load(datapacks, Runnable::run), loader());
        }
        return installedData;
    }

    /** The seed for this server: the helper's, else the one the player gave. */
    private OptionalLong serverSeed(Minecraft minecraft) {
        SeedHelperClient.Reply helper = helperReply();
        if (helper != null && helper.seed() != null) {
            return OptionalLong.of(helper.seed());
        }
        String address = address(minecraft);
        ServerSeeds.Entry entry = address == null ? null : seeds().get(address);
        return entry == null || entry.seed() == null ? OptionalLong.empty() : OptionalLong.of(entry.seed());
    }

    private SeedHelperClient.@Nullable Reply helperReply() {
        Minecraft minecraft = Minecraft.getInstance();
        return helperConnection == minecraft.getConnection() ? helperReply : null;
    }

    /** The helper's reply for this connection. */
    void helperReplied(SeedHelperClient.Reply reply) {
        Minecraft minecraft = Minecraft.getInstance();
        helperConnection = minecraft.getConnection();
        helperReply = reply;
        seedChanged();
    }

    private boolean alwaysForThisServer(Minecraft minecraft) {
        String address = address(minecraft);
        ServerSeeds.Entry entry = address == null ? null : seeds().get(address);
        return entry != null && entry.choice() == ServerSeeds.Choice.ALWAYS;
    }

    /** What the notice says, or null when there is nothing to say (the helper's fingerprint agrees and the seed is the world's). */
    static @Nullable List<Component> notice(PredictionInputs inputs, GenerationFingerprint mine, long seed, long serverSeedHash, TreeSet<String> serverBiomes,
                                            SeedHelperClient.@Nullable Reply helper) {
        List<Component> lines = new ArrayList<>();
        boolean seedRight = BiomeManager.obfuscateSeed(seed) == serverSeedHash;
        TreeSet<String> missing = new TreeSet<>();
        inputs.registries().lookup(Registries.BIOME).ifPresent(mineBiomes -> {
            TreeSet<String> here = new TreeSet<>();
            mineBiomes.listElementIds().forEach(key -> here.add(key.identifier().toString()));
            for (String biome : serverBiomes) {
                if (!here.contains(biome)) {
                    missing.add(Identifier.parse(biome).getNamespace());
                }
            }
        });
        if (helper != null && helper.fingerprint() != null) {
            List<GenerationFingerprint.Difference> differences = mine.differences(GenerationFingerprint.of(helper.fingerprint()));
            if (differences.isEmpty() && seedRight && missing.isEmpty()) {
                return null;
            }
            if (!differences.isEmpty()) {
                List<String> named = new ArrayList<>();
                for (GenerationFingerprint.Difference difference : differences) {
                    named.add(difference.describe());
                    if (named.size() == 8) {
                        named.add("and " + (differences.size() - 8) + " more");
                        break;
                    }
                }
                lines.add(Component.translatable("litelights.seed_notice.differs", String.join(", ", named)));
            }
        } else {
            lines.add(Component.translatable("litelights.seed_notice.unchecked"));
        }
        lines.add(Component.translatable(seedRight ? "litelights.seed_notice.seed_right" : "litelights.seed_notice.seed_wrong"));
        if (!missing.isEmpty()) {
            lines.add(Component.translatable("litelights.seed_notice.biomes_missing", String.join(", ", missing)));
        }
        lines.add(Component.translatable("litelights.seed_notice.ask"));
        return lines;
    }

    /** The folder name predictions are kept under: the dimension, the seed, the fingerprint and the sampler, hashed. */
    static String identity(PredictionInputs inputs, GenerationFingerprint fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String text = inputs.dimension() + "\n" + inputs.seed() + "\n" + fingerprint.hash() + "\n" + SAMPLER_VERSION;
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The seed hash the server sent with the level (vanilla's own, from BiomeManager.obfuscateSeed). */
    static long seedHash(ClientLevel level) {
        try {
            Field field = BiomeManager.class.getDeclaredField("biomeZoomSeed");
            field.setAccessible(true);
            return field.getLong(level.getBiomeManager());
        } catch (ReflectiveOperationException unavailable) {
            return 0L;
        }
    }

    private static double serverTickMillis(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server == null ? -1.0 : server.getAverageTickTimeNanos() / 1.0e6;
    }

    private ThreadPoolExecutor loader() {
        if (loader == null) {
            ThreadPoolExecutor made = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "LiteLights seed generation loader");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            });
            // No thread is kept once nothing is being loaded.
            made.allowCoreThreadTimeOut(true);
            loader = made;
        }
        return loader;
    }

    ServerSeeds seeds() {
        if (seeds == null) {
            seeds = new ServerSeeds(FabricLoader.getInstance().getGameDir().resolve("litelights").resolve("server-seeds.json"));
        }
        return seeds;
    }

    private static @Nullable String address(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null || minecraft.getCurrentServer() == null) {
            return null;
        }
        return ServerSeeds.normalize(minecraft.getCurrentServer().ip);
    }

    // --- Typed seeds and the /seed reply ---

    /** The Far Terrain page's Server seed button. */
    void openSeedEntry() {
        Minecraft minecraft = Minecraft.getInstance();
        String address = address(minecraft);
        if (address == null) {
            return;
        }
        ServerSeeds.Entry entry = seeds().get(address);
        minecraft.gui.setScreen(new SeedEntryScreen(minecraft.gui.screen(), entry != null && entry.seed() != null,
            typed -> {
                seeds().putSeed(address, typed, ServerSeeds.Source.TYPED);
                seedChanged();
            },
            () -> {
                seeds().forget(address);
                seedChanged();
            }));
    }

    /** The player ran {@code /seed} on a server: its reply is listened for for 30 seconds. */
    void commandSent(String command) {
        if (settings.seedGeneration.on() && address(Minecraft.getInstance()) != null && command.trim().equals("seed")) {
            captureUntil = System.nanoTime() + CAPTURE_NANOS;
        }
    }

    /** A system message: if it is the game's own {@code /seed} reply while one is awaited, its seed is kept for this server. */
    void messageReceived(Component message) {
        if (captureUntil == 0 || System.nanoTime() - captureUntil > 0) {
            return;
        }
        OptionalLong seed = seedReply(message, 0);
        String address = address(Minecraft.getInstance());
        if (seed.isPresent() && address != null) {
            captureUntil = 0;
            seeds().putSeed(address, seed.getAsLong(), ServerSeeds.Source.SEED_COMMAND);
            say(Minecraft.getInstance(), Component.translatable("litelights.seed_generation.captured"));
            seedChanged();
        }
    }

    /**
     * The seed in the game's {@code commands.seed.success} message: its argument's copy-to-clipboard
     * value or its text. Only that structured message counts; plain text from a plugin is never parsed.
     */
    static OptionalLong seedReply(Component component, int depth) {
        if (depth > 16) {
            return OptionalLong.empty();
        }
        if (component.getContents() instanceof TranslatableContents translated) {
            if (translated.getKey().equals("commands.seed.success") && translated.getArgs().length == 1) {
                Object argument = translated.getArgs()[0];
                if (argument instanceof Component value) {
                    if (value.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
                        OptionalLong copied = ServerSeeds.parse(copy.value());
                        if (copied.isPresent()) {
                            return copied;
                        }
                    }
                    return ServerSeeds.parse(value.getString().replace("[", "").replace("]", ""));
                }
                return ServerSeeds.parse(String.valueOf(argument));
            }
        }
        for (Component sibling : component.getSiblings()) {
            OptionalLong found = seedReply(sibling, depth + 1);
            if (found.isPresent()) {
                return found;
            }
        }
        return OptionalLong.empty();
    }

    private static void say(Minecraft minecraft, Component text) {
        if (minecraft.player != null) {
            minecraft.gui.hud.getChat().addClientSystemMessage(text);
        }
    }

    // --- Status ---

    /** The Seed row: where the seed comes from, never the seed itself. */
    Component seedStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Component.translatable("litelights.option.far_terrain.seed.none_world");
        }
        if (minecraft.getSingleplayerServer() != null) {
            return Component.translatable("litelights.option.far_terrain.seed.world");
        }
        SeedHelperClient.Reply helper = helperReply();
        if (helper != null && helper.seed() != null) {
            return Component.translatable("litelights.option.far_terrain.seed.helper");
        }
        if (helper != null && !helper.refusal().isEmpty()) {
            return Component.translatable("litelights.option.far_terrain.seed.helper_refused",
                Component.translatable("litelights.option.far_terrain.seed.refusal." + helper.refusal()));
        }
        String address = address(minecraft);
        ServerSeeds.Entry entry = address == null ? null : seeds().get(address);
        if (entry == null || entry.seed() == null) {
            return Component.translatable("litelights.option.far_terrain.seed.none");
        }
        return Component.translatable(entry.source() == ServerSeeds.Source.SEED_COMMAND ? "litelights.option.far_terrain.seed.captured"
            : "litelights.option.far_terrain.seed.typed");
    }

    /** The Predicted row. */
    Component predictedStatus() {
        if (!settings.seedGeneration.on()) {
            return Component.translatable("litelights.option.far_terrain.predicted.off");
        }
        FarLandSession open = session;
        PredictedLand predicted = open == null ? null : open.predictions();
        return switch (phase) {
            case RUNNING -> predicted == null ? Component.translatable("litelights.option.far_terrain.predicted.preparing")
                : Component.translatable("litelights.option.far_terrain.predicted.value",
                    String.format(Locale.ROOT, "%.1f", predicted.squareKilometres()),
                    String.format(Locale.ROOT, "%.1f", predicted.diskBytes() / 1048576.0),
                    Component.translatable(predicted.working() > 0 ? "litelights.option.far_terrain.predicted.working"
                        : "litelights.option.far_terrain.predicted.idle"));
            case WAITING_FOR_SEED -> Component.translatable("litelights.option.far_terrain.predicted.no_seed");
            case PREPARING, OFF -> Component.translatable("litelights.option.far_terrain.predicted.preparing");
            case WAITING_FOR_HELPER -> Component.translatable("litelights.option.far_terrain.predicted.asking_server");
            case ASKING -> Component.translatable("litelights.option.far_terrain.predicted.asking");
            case DECLINED -> Component.translatable("litelights.option.far_terrain.predicted.declined");
            case UNSUPPORTED -> Component.translatable("litelights.option.far_terrain.predicted.unsupported");
            case MISMATCH -> Component.translatable("litelights.option.far_terrain.predicted.mismatch");
            case FAILED -> Component.translatable("litelights.option.far_terrain.predicted.failed", problem == null ? "" : problem);
        };
    }

    /** For QA. */
    Map<String, Object> describe() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("phase", phase.name());
        state.put("problem", problem);
        state.put("mismatchReading", mismatchReading);
        SeedHelperClient.Reply helper = helperReply();
        state.put("helper", helper == null ? null : helper.toString());
        state.put("notice", ready != null && ready.notice() != null ? ready.notice().stream().map(Component::getString).toList() : null);
        return state;
    }
}
