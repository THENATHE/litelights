package com.thenathe.litelights.render.feature.water;

import com.thenathe.litelights.render.feature.water.wake.WakeField;
import com.thenathe.litelights.render.feature.water.wake.WakeSourceList;
import com.thenathe.litelights.render.feature.water.wake.WakeSources;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Runs the wakes (Wakes on the Water page; idea by Goby56, Wakes). Each client tick it moves the
 * field's window with the camera, marks which block columns are open water (from the
 * {@link FluidColumnMap}), reads the boats, swimmers and splashes near the camera and starts the
 * next step on its own thread. The next frame update joins the step (well under a millisecond) and
 * uploads the tiles it changed into {@link FluidDataTexture}'s wake region, so the frames between two
 * ticks blend the same two ticks the game blends its entities between, and a wake keeps up with its
 * boat.
 *
 * <p>On the render thread (client ticks and frame updates); the field is touched only between
 * steps.
 */
final class WakeMap {
    /** One thread of its own: a frame waits for the step, so it must never queue behind other work. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "LiteLights wakes");
        thread.setDaemon(true);
        return thread;
    });

    private final WakeField field = new WakeField();
    private final WakeSources sources = new WakeSources();
    private final WakeSourceList gathered = new WakeSourceList();
    private final WakeSourceList stepping = new WakeSourceList();
    private @Nullable CompletableFuture<WakeField> step;
    private @Nullable ByteBuffer upload;

    /**
     * How far ahead of the camera, along where it looks, the window is centred: most of what can be
     * seen of the water lies in front, and a wake right behind the camera is still kept.
     */
    private static final double AHEAD = 8.0;

    private boolean running;
    private boolean uploadWanted;
    /** The window and texture generation the texture's wake region was last written for. */
    private int shownOriginX;
    private int shownOriginZ;
    private int shownGeneration = -1;
    private boolean anythingShown;

    private long lastTickNanos;
    private long lastUploadNanos;
    private int lastUploadTiles;
    private int lastSources;
    private int totalSources;

    /**
     * One client tick with wakes on. {@code strength} is the Wake strength setting; {@code spray}
     * gets the bow spray, or is null while particles are off.
     */
    void tick(ClientLevel level, Vec3 camera, float cameraYaw, double strength, FluidColumnMap columns, FluidDataTexture texture,
              boolean stepThisTick, WakeSources.@Nullable BowSpray spray) {
        long started = System.nanoTime();
        running = true;
        lastUploadTiles = 0;
        lastUploadNanos = 0;
        // No frame since the last tick (the game runs below 20 frames a second): upload it now.
        finishStep();
        uploadIfWanted(texture);
        if (!stepThisTick) {
            lastTickNanos = System.nanoTime() - started;
            return;
        }
        double yaw = Math.toRadians(cameraYaw);
        Vec3 centre = new Vec3(camera.x - Math.sin(yaw) * AHEAD, camera.y, camera.z + Math.cos(yaw) * AHEAD);
        int originX = (int) Math.floor(centre.x) - WakeField.BLOCKS / 2;
        int originZ = (int) Math.floor(centre.z) - WakeField.BLOCKS / 2;
        field.moveWindow(originX, originZ);
        markOpenWater(columns, originX, originZ);
        gathered.clear();
        sources.collect(level, centre, strength, columns::openWaterSurfaceY, gathered, spray);
        lastSources = gathered.size();
        totalSources += lastSources;
        if (gathered.size() > 0 || !field.isFlat()) {
            stepping.copyFrom(gathered);
            step = CompletableFuture.supplyAsync(this::stepField, WORKER);
            uploadWanted = true;
        }
        lastTickNanos = System.nanoTime() - started;
    }

    /** From the frame update, before the water decides what to draw: the last step's tiles go up. */
    void frame(FluidDataTexture texture) {
        if (!running) {
            return;
        }
        finishStep();
        uploadIfWanted(texture);
    }

    /** Wakes are off, handled elsewhere or can't run: forget everything; the shaders stop reading. */
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        finishStep();
        field.clear();
        sources.clear();
        anythingShown = false;
        shownGeneration = -1;
        uploadWanted = false;
    }

    /** Whether the texture holds wakes the shaders should draw this frame. */
    boolean drawing(FluidDataTexture texture) {
        return running && anythingShown && shownGeneration == texture.generation();
    }

    /** The first cell of the window the texture's wakes were written for. */
    int shownOriginCellX() {
        return shownOriginX * WakeField.CELLS_PER_BLOCK;
    }

    int shownOriginCellZ() {
        return shownOriginZ * WakeField.CELLS_PER_BLOCK;
    }

    private WakeField stepField() {
        field.step(stepping);
        return field;
    }

    private void finishStep() {
        if (step != null) {
            step.join();
            step = null;
        }
    }

    private void markOpenWater(FluidColumnMap columns, int originX, int originZ) {
        for (int z = 0; z < WakeField.BLOCKS; z++) {
            for (int x = 0; x < WakeField.BLOCKS; x++) {
                field.setWater(x, z, !Double.isNaN(columns.openWaterSurfaceY(originX + x, originZ + z)));
            }
        }
    }

    /**
     * Uploads the tiles that changed in the last step, runs of neighbouring tiles in a row at a time.
     * A texture made since the last upload gets every tile.
     */
    private void uploadIfWanted(FluidDataTexture texture) {
        if (texture.view() == null) {
            return;
        }
        boolean newTexture = shownGeneration != texture.generation();
        if (!uploadWanted && !newTexture) {
            return;
        }
        uploadWanted = false;
        long started = System.nanoTime();
        if (newTexture) {
            field.requestFullUpload();
        }
        if (upload == null) {
            upload = MemoryUtil.memAlloc(WakeField.SIZE * WakeField.TILE * 4).order(ByteOrder.LITTLE_ENDIAN);
        }
        int[] texels = field.texels();
        int tiles = 0;
        boolean shown = false;
        for (int tileZ = 0; tileZ < WakeField.TILES; tileZ++) {
            int tileX = 0;
            while (tileX < WakeField.TILES) {
                if (!field.tileChanged(tileX + tileZ * WakeField.TILES)) {
                    tileX++;
                    continue;
                }
                int runStart = tileX;
                while (tileX < WakeField.TILES && field.tileChanged(tileX + tileZ * WakeField.TILES)) {
                    tileX++;
                }
                int x = runStart * WakeField.TILE;
                int width = (tileX - runStart) * WakeField.TILE;
                int z = tileZ * WakeField.TILE;
                ByteBuffer buffer = upload;
                buffer.clear();
                for (int row = 0; row < WakeField.TILE; row++) {
                    int from = x + (z + row) * WakeField.SIZE;
                    for (int column = 0; column < width; column++) {
                        buffer.putInt(texels[from + column]);
                    }
                }
                buffer.flip();
                texture.writeWakes(x, z, width, WakeField.TILE, buffer);
                tiles += tileX - runStart;
            }
        }
        field.markUploaded();
        anythingShown = !field.isFlat();
        shownGeneration = texture.generation();
        shownOriginX = field.originBlockX();
        shownOriginZ = field.originBlockZ();
        lastUploadTiles = tiles;
        lastUploadNanos = System.nanoTime() - started;
    }

    void reset() {
        finishStep();
        field.clear();
        sources.clear();
        anythingShown = false;
        shownGeneration = -1;
        uploadWanted = false;
    }

    // --- For the QA harness ---

    double lastStepMillis() {
        return field.lastStepNanos() / 1.0e6;
    }

    double lastTickMillis() {
        return lastTickNanos / 1.0e6;
    }

    double lastUploadMillis() {
        return lastUploadNanos / 1.0e6;
    }

    int lastUploadTiles() {
        return lastUploadTiles;
    }

    int lastWorkCells() {
        return field.lastWorkCells();
    }

    int lastSources() {
        return lastSources;
    }

    int totalSources() {
        return totalSources;
    }

    WakeSources sources() {
        return sources;
    }
}
