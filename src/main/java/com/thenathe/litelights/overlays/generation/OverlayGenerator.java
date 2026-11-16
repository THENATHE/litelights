package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.EligibleBlocks;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds the overlay pack's files from the current packs and a settings snapshot.
 *
 * <p>Each source block gets one tile set per texture layer and per kind (full overlay or blended
 * seam), and one properties file pointing at it. The properties match every block the source
 * spills onto, so the count of files grows with the number of soft blocks, not with the number of
 * pairs.
 *
 * <p>Sets come in two formats. The variety format is drawn by the overlays module's own Continuity method and
 * holds the pieces {@link VarietyLayout} lists. The plain format is Continuity's own
 * {@code method=overlay} with its 17 tiles; exports always use it, and so does the live pack if
 * the variety method couldn't be set up.
 *
 * <p>Masonry in the Bricks style is cut along its own mortar lines ({@link BrickShape}), so the
 * edge is always whole bricks.
 *
 * <p>Every piece is a full tile, drawn over the whole face, even though most of it is clear. A
 * quad covering only part of a face doesn't share the face's corners, so its depth comes out a
 * hair different from the face below and the two flicker through each other (seen at 64x and up
 * in testing). Drawing the whole face, as Continuity's own overlays do, avoids that.
 */
public final class OverlayGenerator {
    /** The method name the variety format's properties use. */
    public static final String VARIETY_METHOD = "litelights_overlay_variety";
    public static final String CONTINUITY_PROPERTIES_FOLDER = "optifine/ctm/";
    public static final String CONTINUITY_TEXTURE_FOLDER = "textures/overlay/";
    /**
     * Texels of overlays past which variety steps down: 48 million (192 MiB before mipmaps).
     * Measured with full vanilla-sized packs: a 64x pack with normal variety needs about 13
     * million, a 128x pack with low variety about 36 million. The atlas grows in powers of two
     * and every doubling adds to the reload, so this keeps a 128x pack's atlas to one size step
     * above the pack's own. Texture size is never lowered for this; that's the player's setting.
     */
    public static final long VARIETY_TEXEL_BUDGET = 48_000_000L;

    /**
     * What a build needs from the client thread, taken as a snapshot so workers never touch live state.
     *
     * @param format          the files to write
     * @param maxTextureSize  the largest texture side the graphics card allows
     * @param betterGrass     LambdaBetterGrass is installed, so the side faces it redraws are left to it
     */
    public record Input(OverlayConfig config, EligibleBlocks eligible, Map<String, Map<String, String>> defaultStates,
                        String namespace, OverlayFormat format, int maxTextureSize, boolean betterGrass) {

        /**
         * The most overlay texels the block atlas can surely hold for them: a quarter of the atlas's
         * largest side squared, and never more than a 16384-texel side allows. Cards that accept a
         * 32768 texture rarely have the memory to hold one, so the safe side is the smaller one.
         */
        long atlasCeiling() {
            long side = Math.min(maxTextureSize, SAFE_ATLAS_SIDE);
            return side * side / 4;
        }
    }

    private static final int SAFE_ATLAS_SIDE = 16384;

    private record TileSetKey(OverlayFormat format, int textureHash, int size, EdgeStyle style, int reach, int roughness,
                              boolean blend, long seed, VarietyLayout layout, boolean steps) {
    }

    /**
     * One set's pieces as PNGs, by piece index, and the atlas texels they take.
     *
     * @param note for the player, when a brick pattern couldn't be read and a measured one stood in; or {@code null}
     */
    private record BuiltSet(byte[][] pngs, long texels, String note) {
    }

    /**
     * @param part              names one part of a set that had to be split (plain format only), or ""
     * @param betterGrassBlocks receivers whose sides LambdaBetterGrass may redraw (variety format)
     * @param ownSideTiles      those receivers' own side textures: a side still showing one of them
     *                          is one LambdaBetterGrass left alone (both formats)
     * @param ownSnowySideTiles the same for their snowy states (variety format; the plain format
     *                          splits snowy states into a part of their own instead)
     * @param coveredBy         the textures of the source's layers drawn over this one on the same faces
     * @param betterGrassRole   for a source that is one of LambdaBetterGrass's blocks, the part this
     *                          layer plays beside its sides (variety format; see {@link BetterGrassRole}), or ""
     * @param soil              receivers whose own side is the soil under the source's side (variety format)
     * @param firstLayerTints   {@code block=top,side} for receivers with a face of more than one layer:
     *                          the bottom layer's tint index, or {@code *} for a face of one layer
     */
    private record TileSetJob(OverlayPlan.Source source, int rank, FaceLayers.Layer layer, int layerIndex, String faces,
                              boolean blend, Set<String> receivers, boolean steps, String part, Set<String> betterGrassBlocks,
                              Set<String> ownSideTiles, Set<String> ownSnowySideTiles, List<String> coveredBy,
                              String betterGrassRole, Set<String> soil, List<String> firstLayerTints) {

        TileSetJob(OverlayPlan.Source source, int rank, FaceLayers.Layer layer, int layerIndex, String faces, boolean blend,
                   Set<String> receivers, boolean steps, List<String> coveredBy, List<String> firstLayerTints) {
            this(source, rank, layer, layerIndex, faces, blend, receivers, steps, "", Set.of(), Set.of(), Set.of(), coveredBy,
                "", Set.of(), firstLayerTints);
        }

        TileSetJob withBetterGrass(String newFaces, String role, Set<String> blocks, Set<String> tiles, Set<String> snowyTiles) {
            return new TileSetJob(source, rank, layer, layerIndex, newFaces, blend, receivers, steps, part, blocks, tiles,
                snowyTiles, coveredBy, role, soil, firstLayerTints);
        }

        TileSetJob withSoil(Set<String> newSoil) {
            return new TileSetJob(source, rank, layer, layerIndex, faces, blend, receivers, steps, part, betterGrassBlocks,
                ownSideTiles, ownSnowySideTiles, coveredBy, betterGrassRole, newSoil, firstLayerTints);
        }

        /** One part of a plain set, with its own faces, receivers and file name. */
        TileSetJob split(String newFaces, Set<String> newReceivers, String newPart, Set<String> tiles) {
            return new TileSetJob(source, rank, layer, layerIndex, newFaces, blend, newReceivers, false, newPart, Set.of(), tiles,
                Set.of(), coveredBy, "", Set.of(), firstLayerTints);
        }
    }

    /**
     * The part a layer of one of LambdaBetterGrass's blocks plays beside the sides it redraws, as the
     * variety method reads it ({@code variety.betterGrassRole}).
     */
    private enum BetterGrassRole {
        /** The top's layer: drawn on tops as usual, and on sides beside a side it drew full (with the top texture). */
        TOP,
        /** The bottom layer of the block's own side (grass's dirt): only beside a side it left alone. */
        SIDE,
        /** A layer of the own side over the bottom one, or the only one: also onto soil beside a full side. */
        FRINGE,
        /** A block whose top and sides are one texture. */
        BOTH;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How close, in RGB distance, the average colour of a receiver side's bottom half must be to
     * the source side's to count as the soil under it. Measured from vanilla against grass's side
     * (whose bottom half is dirt): dirt, podzol and dirt path 0, mycelium 6, rooted dirt 12,
     * terracotta 19, packed mud 20, coarse dirt 21; granite 27, tuff 46, gravel and stone 68, red
     * sand 67, sand 171.
     */
    private static final double SOIL_DISTANCE = 24;

    private record TileSetOutput(Map<String, byte[]> files, int tiles, long texels, boolean reused) {
    }

    /** Tiles from the previous build, so a reload that changes nothing for a texture reuses its PNGs. */
    private static volatile Map<TileSetKey, BuiltSet> previousSets = Map.of();

    private OverlayGenerator() {
    }

    /**
     * What a build reads from the resource packs: the current packs in the game, fixtures in the
     * unit tests.
     */
    interface Sources {
        Map<String, FaceLayers> layers(OverlayPlan plan, Input input, List<String> skipped, ExecutorService pool);

        ExistingOverlays existingOverlays();

        BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, Input input);

        Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool);
    }

    /** The current packs, as the game shows them. */
    private record PackSources(PackStack packs) implements Sources {
        @Override
        public Map<String, FaceLayers> layers(OverlayPlan plan, Input input, List<String> skipped, ExecutorService pool) {
            return resolveLayers(packs, plan, input, skipped, pool);
        }

        @Override
        public ExistingOverlays existingOverlays() {
            return ExistingOverlays.scan(packs);
        }

        @Override
        public BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, Input input) {
            Set<String> blocks = input.betterGrass() ? BetterGrassBlocks.scan(packs) : Set.of();
            return OverlayGenerator.betterGrassSides(packs, blocks, layers, input);
        }

        @Override
        public Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool) {
            return loadTextures(packs, ids, skipped, pool);
        }
    }

    public static GeneratedFiles generate(PackStack packs, Input input) {
        return generate(OverlayPlan.from(input.config(), input.eligible()), new PackSources(packs), input);
    }

    static GeneratedFiles generate(OverlayPlan plan, Sources sources, Input input) {
        long started = System.nanoTime();
        ExecutorService pool = Workers.pool();
        OverlayConfig config = input.config();
        List<String> skipped = Collections.synchronizedList(new ArrayList<>());
        String drawing = switch (input.format()) {
            case VARIETY -> config.edgeVariety().id();
            case PLAIN -> GenerationReport.DRAWING_PLAIN;
        };
        if (plan.sources().isEmpty()) {
            return new GeneratedFiles(Map.of(), new GenerationReport(0, 0, 0, 0, elapsedMillis(started), 0, 0, 0, 0,
                drawing, List.of(), List.of()));
        }

        Map<String, FaceLayers> layers = sources.layers(plan, input, skipped, pool);
        ExistingOverlays existing = sources.existingOverlays();
        Map<String, Set<String>> texturesByBlock = new HashMap<>();
        layers.forEach((block, faces) -> texturesByBlock.put(block, texturesOf(faces)));

        AtomicInteger covered = new AtomicInteger();
        boolean steps = input.format() == OverlayFormat.VARIETY && config.spillDownSteps;
        List<TileSetJob> jobs = planJobs(plan, layers, existing, texturesByBlock, covered, steps);
        BetterGrassSides betterGrassSides = sources.betterGrassSides(layers, input);
        jobs = deferToBetterGrass(jobs, betterGrassSides, layers, input.format());
        jobs = withSoil(jobs, layers, sources, pool);
        Map<String, SourceTexture> textures = sources.textures(texturesUsed(jobs), skipped, pool);

        List<String> notes = new ArrayList<>();
        Fit fit = fitToAtlas(jobs, textures, input, notes);
        if (input.format() == OverlayFormat.VARIETY) {
            drawing = fit.variety().id();
        }

        List<String> brickNotes = Collections.synchronizedList(new ArrayList<>());
        Map<TileSetKey, BuiltSet> builtSets = new ConcurrentHashMap<>();
        List<CompletableFuture<TileSetOutput>> outputs = new ArrayList<>();
        for (TileSetJob job : jobs) {
            SourceTexture texture = textures.get(job.layer().texture());
            if (texture == null) {
                continue;
            }
            SourceTexture sized = visiblePart(job, texture, textures, fit.sizeLimit());
            VarietyLayout layout = input.format() == OverlayFormat.VARIETY ? fit.variety().layout() : null;
            outputs.add(CompletableFuture.supplyAsync(() -> build(job, sized, layout, input, builtSets, brickNotes), pool));
        }

        Map<String, byte[]> files = new LinkedHashMap<>();
        int tiles = 0;
        int reused = 0;
        int sets = 0;
        long texels = 0;
        for (CompletableFuture<TileSetOutput> future : outputs) {
            TileSetOutput output = future.join();
            if (output != null) {
                files.putAll(output.files());
                tiles += output.tiles();
                texels += output.texels();
                sets++;
                if (output.reused()) {
                    reused++;
                }
            }
        }
        previousSets = Map.copyOf(builtSets);
        notes.addAll(new TreeSet<>(brickNotes));

        Set<String> sourcesUsed = jobs.stream().map(job -> job.source().blockId()).collect(Collectors.toSet());
        long bytes = files.values().stream().mapToLong(content -> content.length).sum();
        GenerationReport report = new GenerationReport(sourcesUsed.size(), sets, tiles, bytes, elapsedMillis(started),
            reused, covered.get(), existing.ruleCount(), texels, drawing, List.copyOf(notes),
            List.copyOf(new LinkedHashSet<>(skipped)));
        return new GeneratedFiles(files, report);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** Reads the models of every block taking part, in parallel. */
    private static Map<String, FaceLayers> resolveLayers(PackStack packs, OverlayPlan plan, Input input,
                                                         List<String> skipped, ExecutorService pool) {
        Set<String> blocks = new LinkedHashSet<>();
        for (OverlayPlan.Source source : plan.sources()) {
            blocks.add(source.blockId());
            blocks.addAll(source.spillOnto());
            blocks.addAll(source.blendWith());
        }
        BlockFaceResolver resolver = new BlockFaceResolver(packs);
        Map<String, FaceLayers> layers = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (String block : blocks) {
            work.add(CompletableFuture.runAsync(() -> {
                try {
                    layers.put(block, resolver.resolve(block, input.defaultStates().getOrDefault(block, Map.of())));
                } catch (BlockFaceResolver.UnusableModelException reason) {
                    skipped.add(block + ": " + reason.getMessage());
                }
            }, pool));
        }
        work.forEach(CompletableFuture::join);
        return layers;
    }

    private static Set<String> texturesOf(FaceLayers faces) {
        Set<String> textures = new HashSet<>();
        faces.top().forEach(layer -> textures.add(layer.texture()));
        faces.side().forEach(layer -> textures.add(layer.texture()));
        return textures;
    }

    private static List<TileSetJob> planJobs(OverlayPlan plan, Map<String, FaceLayers> layers, ExistingOverlays existing,
                                             Map<String, Set<String>> texturesByBlock, AtomicInteger covered, boolean steps) {
        List<TileSetJob> jobs = new ArrayList<>();
        int count = plan.sources().size();
        for (int index = 0; index < count; index++) {
            OverlayPlan.Source source = plan.sources().get(index);
            FaceLayers faces = layers.get(source.blockId());
            if (faces == null) {
                continue;
            }
            // Softer sources sort later, so Continuity draws their overlays on top where two meet.
            int rank = count - 1 - index;
            for (boolean blend : new boolean[] {false, true}) {
                Set<String> receivers = new LinkedHashSet<>();
                for (String receiver : blend ? source.blendWith() : source.spillOnto()) {
                    if (!layers.containsKey(receiver)) {
                        continue;
                    }
                    if (existing.covers(receiver, source.blockId(), texturesByBlock::get)) {
                        covered.incrementAndGet();
                    } else {
                        receivers.add(receiver);
                    }
                }
                if (receivers.isEmpty()) {
                    continue;
                }
                // Steps continue the source's side face, so they belong to the side layers' sets. A
                // brick source's wall doesn't run on across the floor in front of it, so it has none.
                boolean sideSteps = steps && !blend && source.style() != EdgeStyle.BRICKS;
                List<String> firstLayerTints = firstLayerTints(receivers, layers);
                if (faces.top().equals(faces.side())) {
                    for (int i = 0; i < faces.top().size(); i++) {
                        jobs.add(new TileSetJob(source, rank, faces.top().get(i), i, "top sides", blend, receivers, sideSteps,
                            texturesAbove(faces.top(), i), firstLayerTints));
                    }
                } else {
                    for (int i = 0; i < faces.top().size(); i++) {
                        jobs.add(new TileSetJob(source, rank, faces.top().get(i), i, "top", blend, receivers, false,
                            texturesAbove(faces.top(), i), firstLayerTints));
                    }
                    for (int i = 0; i < faces.side().size(); i++) {
                        jobs.add(new TileSetJob(source, rank, faces.side().get(i), faces.top().size() + i, "sides", blend,
                            receivers, sideSteps, texturesAbove(faces.side(), i), firstLayerTints));
                    }
                }
            }
        }
        return jobs;
    }

    /**
     * For each receiver whose top or sides are drawn in more than one layer, the tint index of each
     * face's bottom layer, so the variety method draws its pieces on that quad only.
     */
    private static List<String> firstLayerTints(Set<String> receivers, Map<String, FaceLayers> layers) {
        List<String> tints = new ArrayList<>();
        for (String receiver : receivers) {
            FaceLayers faces = layers.get(receiver);
            if (faces != null && (faces.top().size() > 1 || faces.side().size() > 1)) {
                tints.add(receiver + "=" + bottomTint(faces.top()) + "," + bottomTint(faces.side()));
            }
        }
        return List.copyOf(tints);
    }

    private static String bottomTint(List<FaceLayers.Layer> face) {
        return face.size() > 1 ? Integer.toString(face.getFirst().tintIndex()) : "*";
    }

    /** The textures of the layers drawn over layer {@code index} of a face, bottom first. */
    private static List<String> texturesAbove(List<FaceLayers.Layer> layers, int index) {
        List<String> above = new ArrayList<>();
        for (FaceLayers.Layer layer : layers.subList(index + 1, layers.size())) {
            above.add(layer.texture());
        }
        return List.copyOf(above);
    }

    /**
     * The own side textures of each receiver LambdaBetterGrass may redraw, for its plain states and,
     * where it has a {@code snowy} property, its snowy ones: a snowy grass block's own side is the
     * snowy side, and it's only LambdaBetterGrass's when the block isn't snowy.
     */
    record BetterGrassSides(Map<String, Set<String>> plain, Map<String, Set<String>> snowy) {
        static final BetterGrassSides NONE = new BetterGrassSides(Map.of(), Map.of());

        Set<String> blocks() {
            return plain.keySet();
        }
    }

    private static BetterGrassSides betterGrassSides(PackStack packs, Set<String> owned, Map<String, FaceLayers> layers,
                                                     Input input) {
        Map<String, Set<String>> plain = new TreeMap<>();
        Map<String, Set<String>> snowy = new TreeMap<>();
        BlockFaceResolver resolver = new BlockFaceResolver(packs);
        for (String block : owned) {
            FaceLayers faces = layers.get(block);
            if (faces == null) {
                continue;
            }
            plain.put(block, sideTextures(faces));
            Map<String, String> defaults = input.defaultStates().getOrDefault(block, Map.of());
            if (defaults.containsKey("snowy")) {
                Map<String, String> snowyState = new LinkedHashMap<>(defaults);
                snowyState.put("snowy", "true");
                try {
                    snowy.put(block, sideTextures(resolver.resolve(block, snowyState)));
                } catch (BlockFaceResolver.UnusableModelException reason) {
                    // No readable snowy model: its snowy sides are left to LambdaBetterGrass, as before.
                }
            }
        }
        return new BetterGrassSides(plain, snowy);
    }

    private static Set<String> sideTextures(FaceLayers faces) {
        Set<String> textures = new TreeSet<>();
        faces.side().forEach(layer -> textures.add(layer.texture()));
        return textures;
    }

    /**
     * Follows what LambdaBetterGrass draws on the side faces of its blocks. It redraws a face by
     * swapping that face's sprite (its full side, blend masks or snowy side), so a side of one of
     * its blocks still showing the block's own side textures for its state is a face it left alone.
     *
     * <p>Variety sets carry each such receiver's own side textures, and the drawing method compares
     * them with the face it's given. A set whose source is one of LambdaBetterGrass's blocks also
     * gets its role ({@link BetterGrassRole}): the method works out what LambdaBetterGrass drew on
     * the source's sides around a face and spills the top layer from full sides, the own side from
     * sides it left alone, and nothing from blended ones. The top layer's set is matched on sides
     * too for that. Sets from other sources draw on its sides whatever it did there.
     *
     * <p>The plain format can only say which receiver faces a set draws on, with Continuity's
     * {@code matchTiles}, not what a neighbour's side looks like: the receivers' sides move into
     * sets of their own, one for plain states and one for snowy states, that only match quads still
     * showing the matching own side textures. That is done for sources that are LambdaBetterGrass's
     * blocks only; other sources draw over its sides, as in the variety format.
     */
    private static List<TileSetJob> deferToBetterGrass(List<TileSetJob> jobs, BetterGrassSides sides,
                                                       Map<String, FaceLayers> layers, OverlayFormat format) {
        if (sides.blocks().isEmpty()) {
            return jobs;
        }
        List<TileSetJob> result = new ArrayList<>();
        for (TileSetJob job : jobs) {
            boolean betterGrassSource = sides.blocks().contains(job.source().blockId());
            Set<String> affected = new LinkedHashSet<>(job.receivers());
            affected.retainAll(sides.blocks());
            Set<String> ownSides = new TreeSet<>();
            Set<String> ownSnowySides = new TreeSet<>();
            for (String receiver : affected) {
                ownSides.addAll(sides.plain().get(receiver));
                ownSnowySides.addAll(sides.snowy().getOrDefault(receiver, Set.of()));
            }
            if (format == OverlayFormat.VARIETY) {
                BetterGrassRole role = betterGrassSource ? roleOf(job, layers.get(job.source().blockId())) : null;
                String faces = role == BetterGrassRole.TOP ? "top sides" : job.faces();
                if (role == null && (affected.isEmpty() || faces.equals("top"))) {
                    result.add(job);
                } else {
                    result.add(job.withBetterGrass(faces, role == null ? "" : role.id(), Set.copyOf(affected),
                        Set.copyOf(ownSides), Set.copyOf(ownSnowySides)));
                }
                continue;
            }
            if (!betterGrassSource || affected.isEmpty() || job.faces().equals("top")) {
                result.add(job);
                continue;
            }
            Set<String> remaining = new LinkedHashSet<>(job.receivers());
            remaining.removeAll(sides.blocks());
            boolean topToo = job.faces().equals("top sides");
            if (topToo) {
                result.add(job.split("top", job.receivers(), "top", Set.of()));
            }
            if (!remaining.isEmpty()) {
                result.add(job.split("sides", remaining, topToo ? "sides" : "", Set.of()));
            }
            Set<String> plainStates = new LinkedHashSet<>();
            Set<String> snowyStates = new LinkedHashSet<>();
            for (String receiver : affected) {
                boolean hasSnowySides = sides.snowy().containsKey(receiver);
                plainStates.add(hasSnowySides ? receiver + ":snowy=false" : receiver);
                if (hasSnowySides) {
                    snowyStates.add(receiver + ":snowy=true");
                }
            }
            result.add(job.split("sides", plainStates, "own", ownSides));
            if (!snowyStates.isEmpty()) {
                result.add(job.split("sides", snowyStates, "ownsnowy", ownSnowySides));
            }
        }
        return result;
    }

    private static BetterGrassRole roleOf(TileSetJob job, FaceLayers source) {
        return switch (job.faces()) {
            case "top sides" -> BetterGrassRole.BOTH;
            case "top" -> BetterGrassRole.TOP;
            default -> source.side().size() == 1 || job.layerIndex() > source.top().size()
                ? BetterGrassRole.FRINGE
                : BetterGrassRole.SIDE;
        };
    }

    /**
     * Finds, for each set whose source is one of LambdaBetterGrass's blocks, the receivers that read
     * as the soil under the source's side: their own side's bottom half is close in colour to the
     * source side's (dirt under grass; coarse and rooted dirt, packed mud). Beside a full side they
     * take the source's fringe, so grass hangs over their top corners as a grass block's side does,
     * rather than a strip of top texture down their whole height.
     */
    private static List<TileSetJob> withSoil(List<TileSetJob> jobs, Map<String, FaceLayers> layers, Sources sources,
                                             ExecutorService pool) {
        Map<String, Set<String>> receiversBySource = new LinkedHashMap<>();
        for (TileSetJob job : jobs) {
            if (job.betterGrassRole().equals(BetterGrassRole.TOP.id()) || job.betterGrassRole().equals(BetterGrassRole.FRINGE.id())) {
                receiversBySource.computeIfAbsent(job.source().blockId(), source -> new LinkedHashSet<>()).addAll(job.receivers());
            }
        }
        if (receiversBySource.isEmpty()) {
            return jobs;
        }
        Set<String> ids = new LinkedHashSet<>();
        receiversBySource.forEach((source, receivers) -> {
            ids.add(bottomSideTexture(layers.get(source)));
            receivers.forEach(receiver -> ids.add(bottomSideTexture(layers.get(receiver))));
        });
        ids.remove(null);
        // A texture that can't be read here only means that receiver isn't counted as soil.
        Map<String, SourceTexture> textures = sources.textures(ids, new ArrayList<>(), pool);
        Map<String, Set<String>> soilBySource = new HashMap<>();
        receiversBySource.forEach((source, receivers) -> {
            SourceTexture sourceSide = textures.get(bottomSideTexture(layers.get(source)));
            Set<String> soil = new TreeSet<>();
            for (String receiver : receivers) {
                SourceTexture receiverSide = textures.get(bottomSideTexture(layers.get(receiver)));
                if (sourceSide != null && receiverSide != null && isSoil(sourceSide, receiverSide)) {
                    soil.add(receiver);
                }
            }
            soilBySource.put(source, Set.copyOf(soil));
        });
        List<TileSetJob> result = new ArrayList<>();
        for (TileSetJob job : jobs) {
            Set<String> soil = soilBySource.get(job.source().blockId());
            result.add(soil == null || job.betterGrassRole().isEmpty() ? job : job.withSoil(soil));
        }
        return result;
    }

    private static String bottomSideTexture(FaceLayers faces) {
        return faces == null || faces.side().isEmpty() ? null : faces.side().getFirst().texture();
    }

    static boolean isSoil(SourceTexture sourceSide, SourceTexture receiverSide) {
        double[] source = bottomHalfColour(sourceSide);
        double[] receiver = bottomHalfColour(receiverSide);
        return source != null && receiver != null
            && Math.sqrt(Math.pow(source[0] - receiver[0], 2) + Math.pow(source[1] - receiver[1], 2)
                + Math.pow(source[2] - receiver[2], 2)) <= SOIL_DISTANCE;
    }

    /** The average red, green and blue of a texture's opaque texels in its bottom half, or {@code null} if there are none. */
    private static double[] bottomHalfColour(SourceTexture texture) {
        int size = texture.size();
        double[] total = new double[3];
        int counted = 0;
        for (int index = size / 2 * size; index < size * size; index++) {
            int texel = texture.pixels()[index];
            if ((texel >>> 24) < 128) {
                continue;
            }
            total[0] += (texel >> 16) & 0xFF;
            total[1] += (texel >> 8) & 0xFF;
            total[2] += texel & 0xFF;
            counted++;
        }
        if (counted == 0) {
            return null;
        }
        return new double[] {total[0] / counted, total[1] / counted, total[2] / counted};
    }

    /** Every texture the jobs draw or clear under. */
    private static Set<String> texturesUsed(List<TileSetJob> jobs) {
        Set<String> ids = new LinkedHashSet<>();
        jobs.forEach(job -> {
            ids.add(job.layer().texture());
            ids.addAll(job.coveredBy());
        });
        return ids;
    }

    /** Loads each texture once, in parallel. Unusable ones are reported and left out. */
    private static Map<String, SourceTexture> loadTextures(PackStack packs, Set<String> ids, List<String> skipped,
                                                           ExecutorService pool) {
        Map<String, Optional<SourceTexture>> loaded = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (String id : ids) {
            work.add(CompletableFuture.runAsync(() -> loaded.put(id, load(packs, id, skipped)), pool));
        }
        work.forEach(CompletableFuture::join);
        Map<String, SourceTexture> textures = new HashMap<>();
        loaded.forEach((id, texture) -> texture.ifPresent(found -> textures.put(id, found)));
        return textures;
    }

    private static Optional<SourceTexture> load(PackStack packs, String textureId, List<String> skipped) {
        try {
            return Optional.of(SourceTexture.load(packs, textureId));
        } catch (SourceTexture.UnusableTextureException reason) {
            skipped.add(reason.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The part of a layer's texture that the source's own model lets show: the texture at the
     * build's size, cleared wherever a layer drawn over it on the same faces is solid.
     *
     * <p>Those hidden texels can hold colour that was never meant to be seen. A grass block's side
     * has its fringe painted in a fixed default green under the tinted fringe layer, for when that
     * layer is missing. Two coplanar quads don't always hide each other exactly (texel edges,
     * smaller mipmap levels, other mods' renderers), and an overlay that draws the hidden green
     * shows grass that doesn't take the biome's colour. Cleared, those spots show only the tinted
     * layer above, or the receiving block where nothing is drawn.
     */
    private static SourceTexture visiblePart(TileSetJob job, SourceTexture texture, Map<String, SourceTexture> textures,
                                             int sizeLimit) {
        SourceTexture visible = texture.scaledTo(Math.min(texture.size(), sizeLimit));
        for (String coveringId : job.coveredBy()) {
            SourceTexture covering = textures.get(coveringId);
            if (covering != null) {
                visible = visible.withoutTexelsUnder(covering.scaledTo(Math.min(covering.size(), sizeLimit)));
            }
        }
        return visible;
    }

    /** The variety level and size cap a build ends up with. */
    private record Fit(EdgeVariety variety, int sizeLimit) {
    }

    /**
     * Picks the variety level and size limit for a build. The player's size limit always applies.
     * Past {@link #VARIETY_TEXEL_BUDGET}, variety steps down (as far as off). Only if even that
     * would overflow what the graphics card can hold ({@link Input#atlasCeiling}) are the largest
     * textures drawn smaller, so the atlas can't fail to build. Each step is noted for the player.
     */
    private static Fit fitToAtlas(List<TileSetJob> jobs, Map<String, SourceTexture> textures, Input input, List<String> notes) {
        OverlayConfig config = input.config();
        int largest = 0;
        for (SourceTexture texture : textures.values()) {
            largest = Math.max(largest, texture.size());
        }
        int sizeLimit = config.overlaySizeLimit();
        if (largest > sizeLimit) {
            notes.add("Textures larger than " + sizeLimit + "x are drawn at " + sizeLimit + "x (Largest overlay size in settings).");
        }
        int fitted = Math.min(sizeLimit, largest);
        EdgeVariety chosen = input.format() == OverlayFormat.PLAIN ? EdgeVariety.OFF : config.edgeVariety();
        EdgeVariety variety = chosen;
        while (estimateTexels(jobs, textures, input, variety, fitted) > VARIETY_TEXEL_BUDGET && variety != EdgeVariety.OFF) {
            variety = variety.lower();
        }
        while (estimateTexels(jobs, textures, input, variety, fitted) > input.atlasCeiling() && fitted > 16) {
            fitted /= 2;
        }
        if (variety != chosen) {
            notes.add("Edge variety lowered from " + chosen.id() + " to " + variety.id()
                + " to keep the block atlas, and the reload, from growing too much.");
        }
        if (fitted < Math.min(sizeLimit, largest)) {
            notes.add("Overlays from textures larger than " + fitted + "x are drawn at " + fitted
                + "x: larger would push the block atlas past a safe size (16384 texels a side).");
        }
        return new Fit(variety, Math.min(sizeLimit, fitted));
    }

    /** An upper bound on the texels a build would add to the block atlas. */
    private static long estimateTexels(List<TileSetJob> jobs, Map<String, SourceTexture> textures, Input input,
                                       EdgeVariety variety, int sizeLimit) {
        long total = 0;
        VarietyLayout layout = variety.layout();
        int classes = layout.classes();
        for (TileSetJob job : jobs) {
            SourceTexture texture = textures.get(job.layer().texture());
            if (texture == null) {
                continue;
            }
            long size = Math.min(texture.size(), sizeLimit);
            if (input.format() == OverlayFormat.PLAIN) {
                total += TileLayout.TILE_COUNT * size * size;
                continue;
            }
            long pieces = layout.bandCount();
            if (!job.blend()) {
                pieces += 4L * classes;
            }
            if (job.steps()) {
                pieces += 4;
            }
            total += pieces * size * size;
        }
        return total;
    }

    private static TileSetOutput build(TileSetJob job, SourceTexture texture, VarietyLayout layout, Input input,
                                       Map<TileSetKey, BuiltSet> builtSets, List<String> notes) {
        OverlayConfig config = input.config();
        int roughness = Math.clamp(config.edgeRoughness, 0, 100);
        long seed = job.source().blockId().hashCode();
        TileSetKey key = new TileSetKey(input.format(), Arrays.hashCode(texture.pixels()), texture.size(), job.source().style(),
            config.clampedReach(), roughness, job.blend(), seed, layout, job.steps());

        BuiltSet set = builtSets.get(key);
        boolean reused = false;
        if (set == null) {
            set = previousSets.get(key);
            reused = set != null;
        }
        if (set == null) {
            set = buildSet(job, texture, layout, input, seed);
        }
        builtSets.put(key, set);
        if (set.note() != null) {
            notes.add(set.note());
        }
        int tileCount = (int) Arrays.stream(set.pngs()).filter(Objects::nonNull).count();
        if (tileCount == 0) {
            return null;
        }
        Map<String, byte[]> files = switch (input.format()) {
            case VARIETY -> varietyFiles(job, input, set, layout, texture.size());
            case PLAIN -> plainFiles(job, input, set.pngs());
        };
        return new TileSetOutput(files, tileCount, set.texels(), reused);
    }

    private static BuiltSet buildSet(TileSetJob job, SourceTexture texture, VarietyLayout layout, Input input, long seed) {
        OverlayConfig config = input.config();
        double roughness = Math.clamp(config.edgeRoughness, 0, 100) / 100.0;
        EdgeStyle style = job.source().style();
        OverlayShape shape;
        EdgeShape steps = null;
        String note = null;
        if (style == EdgeStyle.BRICKS) {
            BrickGrid grid = BrickGrid.of(texture);
            note = presetNote(grid, texture.id());
            shape = new BrickShape(grid, config.clampedReach(), roughness, job.blend(), seed);
        } else {
            EdgeShape edge = new EdgeShape(style, texture.size(), config.clampedReach(), roughness, job.blend(), seed);
            shape = edge;
            steps = job.steps() ? edge : null;
        }
        BuiltSet set = switch (input.format()) {
            case VARIETY -> buildVariety(texture, shape, steps, layout);
            case PLAIN -> buildPlain(texture, shape);
        };
        return note == null ? set : new BuiltSet(set.pngs(), set.texels(), note);
    }

    private static String presetNote(BrickGrid grid, String textureId) {
        return switch (grid.origin()) {
            case DETECTED -> null;
            case BRICKS_PRESET -> "No brick pattern could be read from " + textureId + "; its overlays follow vanilla's bricks.";
            case STONE_BRICKS_PRESET -> "No brick pattern could be read from " + textureId
                + "; its overlays follow vanilla's stone bricks.";
        };
    }

    private static BuiltSet buildPlain(SourceTexture texture, OverlayShape shape) {
        int size = texture.size();
        int[][] tiles = TileComposer.compose(texture.pixels(), size, shape);
        byte[][] pngs = new byte[tiles.length][];
        long texels = 0;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != null) {
                pngs[i] = PngWriter.encode(tiles[i], size, size);
                texels += (long) size * size;
            }
        }
        return new BuiltSet(pngs, texels, null);
    }

    /**
     * Draws every piece of a variety set; pieces that come out empty are left out.
     *
     * @param steps the shape of the step pieces, or {@code null} for a set without steps
     */
    private static BuiltSet buildVariety(SourceTexture texture, OverlayShape shape, EdgeShape steps, VarietyLayout layout) {
        int size = texture.size();
        int classes = layout.classes();
        byte[][] masks = new byte[layout.pieceCount()][];
        for (int direction = 0; direction < 4; direction++) {
            for (int start = 0; start < classes; start++) {
                for (int end = 0; end < classes; end++) {
                    for (int wave = 0; wave < layout.waves(); wave++) {
                        masks[layout.band(direction, start, end, wave)] = shape.band(direction, start, end, wave, classes);
                    }
                }
            }
            if (steps != null) {
                masks[layout.step(direction)] = steps.band(direction, 0, 0, 0, 1);
            }
        }
        if (!shape.isBlend()) {
            for (int corner = 0; corner < 4; corner++) {
                for (int cornerClass = 0; cornerClass < classes; cornerClass++) {
                    masks[layout.innerCorner(corner, cornerClass)] = shape.innerCorner(corner, cornerClass, classes);
                }
            }
        }

        byte[][] pngs = new byte[masks.length][];
        long texels = 0;
        for (int piece = 0; piece < masks.length; piece++) {
            if (masks[piece] == null) {
                continue;
            }
            TileComposer.Mapping mapping = layout.isStep(piece)
                ? TileComposer.Mapping.step(layout.anchors(piece)[0])
                : TileComposer.Mapping.FACE;
            int[] pixels = TileComposer.compose(texture.pixels(), size, masks[piece], mapping);
            if (pixels != null) {
                pngs[piece] = PngWriter.encode(pixels, size, size);
                texels += (long) size * size;
            }
        }
        return new BuiltSet(pngs, texels, null);
    }

    private static Map<String, byte[]> plainFiles(TileSetJob job, Input input, byte[][] pngs) {
        String namespace = input.namespace();
        String spriteFolder = spriteFolder(job);

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> tiles = new ArrayList<>();
        for (int tile = 0; tile < pngs.length; tile++) {
            if (pngs[tile] == null) {
                // An empty tile would only cost a quad; Continuity treats <skip> as "draw nothing".
                tiles.add("<skip>");
            } else {
                files.put("assets/" + namespace + "/textures/" + spriteFolder + "/" + tile + ".png", pngs[tile]);
                tiles.add(namespace + ":" + spriteFolder + "/" + tile);
            }
        }

        StringBuilder properties = header(job, input, "overlay");
        properties.append("tiles=").append(String.join(" ", tiles)).append('\n');
        files.put(propertiesPath(job, namespace), properties.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /**
     * The variety format. {@code tiles} names every piece so Continuity puts them all in the block
     * atlas (it is padded to the 17 the overlay method checks for); the {@code variety.*} keys tell
     * the module's method which piece is which.
     */
    private static Map<String, byte[]> varietyFiles(TileSetJob job, Input input, BuiltSet set, VarietyLayout layout, int size) {
        String namespace = input.namespace();
        String spriteFolder = spriteFolder(job);

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> tiles = new ArrayList<>();
        List<String> pieces = new ArrayList<>();
        boolean hasSteps = false;
        for (int piece = 0; piece < set.pngs().length; piece++) {
            if (set.pngs()[piece] != null) {
                files.put("assets/" + namespace + "/textures/" + spriteFolder + "/" + piece + ".png", set.pngs()[piece]);
                tiles.add(namespace + ":" + spriteFolder + "/" + piece);
                pieces.add(Integer.toString(piece));
                hasSteps |= layout.isStep(piece);
            }
        }
        List<String> padded = new ArrayList<>(tiles);
        while (padded.size() < TileLayout.TILE_COUNT) {
            padded.add(tiles.getFirst());
        }

        StringBuilder properties = header(job, input, VARIETY_METHOD);
        properties.append("tiles=").append(String.join(" ", padded)).append('\n');
        properties.append("variety.layout=").append(layout.classes()).append(' ').append(layout.waves()).append('\n');
        properties.append("variety.size=").append(size).append('\n');
        properties.append("variety.blend=").append(job.blend()).append('\n');
        properties.append("variety.steps=").append(hasSteps).append('\n');
        if (!job.betterGrassBlocks().isEmpty()) {
            properties.append("variety.betterGrassBlocks=").append(String.join(" ", job.betterGrassBlocks())).append('\n');
            properties.append("variety.betterGrassOwnSides=").append(String.join(" ", job.ownSideTiles())).append('\n');
            properties.append("variety.betterGrassOwnSnowySides=").append(String.join(" ", job.ownSnowySideTiles()))
                .append('\n');
        }
        if (!job.betterGrassRole().isEmpty()) {
            properties.append("variety.betterGrassRole=").append(job.betterGrassRole()).append('\n');
        }
        if (!job.soil().isEmpty()) {
            properties.append("variety.betterGrassSoil=").append(String.join(" ", job.soil())).append('\n');
        }
        if (!job.firstLayerTints().isEmpty()) {
            properties.append("variety.firstLayerTints=").append(String.join(" ", job.firstLayerTints())).append('\n');
        }
        properties.append("variety.folder=").append(namespace).append(':').append(spriteFolder).append('/').append('\n');
        properties.append("variety.pieces=").append(String.join(" ", pieces)).append('\n');
        files.put(propertiesPath(job, namespace), properties.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static String spriteFolder(TileSetJob job) {
        return "overlay/" + job.source().blockId().replace(':', '/') + "/" + kind(job);
    }

    private static String kind(TileSetJob job) {
        return (job.blend() ? "blend" : "spill") + job.layerIndex() + job.part();
    }

    private static String propertiesPath(TileSetJob job, String namespace) {
        String name = String.format("%03d_%s_%s", job.rank(), job.source().blockId().replace(':', '_'), kind(job));
        return "assets/" + namespace + "/optifine/ctm/" + name + ".properties";
    }

    private static StringBuilder header(TileSetJob job, Input input, String method) {
        StringBuilder properties = new StringBuilder();
        properties.append("# Generated by LiteLights overlays (Dynamic Overlays): ").append(job.source().blockId())
            .append(job.blend() ? " blends into " : " spills onto ").append(job.receivers().size()).append(" blocks\n");
        properties.append("method=").append(method).append('\n');
        properties.append("matchBlocks=").append(String.join(" ", job.receivers())).append('\n');
        properties.append("connectBlocks=").append(job.source().blockId())
            .append(stateSuffix(input.defaultStates().get(job.source().blockId()))).append('\n');
        properties.append("faces=").append(job.faces()).append('\n');
        if (!job.ownSideTiles().isEmpty() && job.betterGrassBlocks().isEmpty()) {
            // Plain format: only quads still showing the receivers' own side textures, which are
            // exactly the faces LambdaBetterGrass didn't redraw.
            properties.append("matchTiles=").append(String.join(" ", job.ownSideTiles())).append('\n');
        }
        properties.append("layer=cutout\n");
        if (job.layer().tinted()) {
            properties.append("tintIndex=").append(job.layer().tintIndex()).append('\n');
            properties.append("tintBlock=").append(job.source().blockId()).append('\n');
        }
        return properties;
    }

    /**
     * Pins the source to its default state, so a snowy grass block (which shows a snowy side)
     * doesn't spill green grass.
     */
    private static String stateSuffix(Map<String, String> defaultState) {
        if (defaultState == null || defaultState.isEmpty()) {
            return "";
        }
        StringBuilder suffix = new StringBuilder();
        defaultState.forEach((property, value) -> suffix.append(':').append(property).append('=').append(value));
        return suffix.toString();
    }
}
