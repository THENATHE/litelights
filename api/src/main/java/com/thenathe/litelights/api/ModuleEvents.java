package com.thenathe.litelights.api;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The points in core's frame and world where a module joins in (docs/ARCHITECTURE.md, "The frame,
 * in order" and "Modules"). Core fires each of them at a fixed place among its own streams, so a
 * module's listeners always run in the same order against them. Render thread only.
 */
public final class ModuleEvents {
    private ModuleEvents() {
    }

    /**
     * Once a frame, before the LiteFrame block is uploaded: after every core stream has filled its
     * values and bits, and before the per-dimension switches clear theirs. No render pass is
     * open, so GPU uploads are allowed. If a listener throws, core logs it (at most every 5 s) and
     * the event's later listeners miss that frame.
     */
    public static final Event<FrameUpdate> FRAME_UPDATE = EventFactory.createArrayBacked(FrameUpdate.class,
        listeners -> (frame, settings) -> {
            for (FrameUpdate listener : listeners) {
                listener.onFrameUpdate(frame, settings);
            }
        });

    /**
     * The split before translucent terrain, after the solid world: the place to draw into the main
     * target before water's scene copy and TAA's opaque depth are taken, so both see it. Core only
     * splits vanilla's classic transparency pass when some listener asks for it.
     */
    public static final Event<BeforeTranslucents> BEFORE_TRANSLUCENTS = EventFactory.createArrayBacked(BeforeTranslucents.class,
        listeners -> new BeforeTranslucents() {
            @Override
            public boolean wantsPassBeforeTranslucents() {
                for (BeforeTranslucents listener : listeners) {
                    if (listener.wantsPassBeforeTranslucents()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void beforeTranslucents(RenderTarget mainTarget) {
                for (BeforeTranslucents listener : listeners) {
                    listener.beforeTranslucents(mainTarget);
                }
            }
        });

    /**
     * Just after water's scene copy, before translucent terrain: the place to draw translucent
     * surfaces over the copied scene, as near water is drawn over it (far terrain's water). Core
     * splits vanilla's pass for a listener that asks, as for {@link #BEFORE_TRANSLUCENTS}, and
     * takes the scene copy for it whenever water's settings use one, even with no water near the
     * camera.
     */
    public static final Event<AfterSceneCopy> AFTER_SCENE_COPY = EventFactory.createArrayBacked(AfterSceneCopy.class,
        listeners -> new AfterSceneCopy() {
            @Override
            public boolean wantsPassAfterSceneCopy() {
                for (AfterSceneCopy listener : listeners) {
                    if (listener.wantsPassAfterSceneCopy()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean wantsSceneCopy() {
                for (AfterSceneCopy listener : listeners) {
                    if (listener.wantsPassAfterSceneCopy() && listener.wantsSceneCopy()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void afterSceneCopy(RenderTarget mainTarget) {
                for (AfterSceneCopy listener : listeners) {
                    listener.afterSceneCopy(mainTarget);
                }
            }
        });

    /**
     * The block atlas was stitched again (start-up, resource reload): sprites may have moved, and
     * the block models were made again with it.
     */
    public static final Event<BlockAtlasStitched> BLOCK_ATLAS_STITCHED = EventFactory.createArrayBacked(BlockAtlasStitched.class,
        listeners -> atlas -> {
            for (BlockAtlasStitched listener : listeners) {
                listener.onBlockAtlasStitched(atlas);
            }
        });

    /** A block in the client level changed (not a light-only update). */
    public static final Event<BlockChanged> BLOCK_CHANGED = EventFactory.createArrayBacked(BlockChanged.class,
        listeners -> (level, pos, oldState, newState) -> {
            for (BlockChanged listener : listeners) {
                listener.onBlockChanged(level, pos, oldState, newState);
            }
        });

    /**
     * The client's level changed: the player joined a world, or moved to another dimension (a new
     * level object either way). Fired on the client thread before the first frame of the new level.
     */
    public static final Event<LevelChanged> LEVEL_CHANGED = EventFactory.createArrayBacked(LevelChanged.class,
        listeners -> level -> {
            for (LevelChanged listener : listeners) {
                listener.onLevelChanged(level);
            }
        });

    /** The player left the world: let go of it, and of the GPU memory that only mattered there. */
    public static final Event<WorldLeft> WORLD_LEFT = EventFactory.createArrayBacked(WorldLeft.class,
        listeners -> () -> {
            for (WorldLeft listener : listeners) {
                listener.onWorldLeft();
            }
        });

    @FunctionalInterface
    public interface FrameUpdate {
        void onFrameUpdate(FrameAccess frame, FarTerrainSettings settings);
    }

    public interface BeforeTranslucents {
        /** Whether this frame needs the pass; asked before vanilla's pass is split for it. */
        boolean wantsPassBeforeTranslucents();

        /** With no render pass open. Catches its own failures. */
        void beforeTranslucents(RenderTarget mainTarget);
    }

    public interface AfterSceneCopy {
        /** Whether this frame draws over the copied scene; asked before vanilla's pass is split for it. */
        boolean wantsPassAfterSceneCopy();

        /**
         * Whether that pass needs the copy, so it is taken (when water's settings allow one) even
         * with no water near the camera; without it the pass gets the copy only when water takes one.
         */
        default boolean wantsSceneCopy() {
            return true;
        }

        /** With no render pass open, the scene copy bound (when water's settings take one). Catches its own failures. */
        void afterSceneCopy(RenderTarget mainTarget);
    }

    @FunctionalInterface
    public interface BlockChanged {
        void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState);
    }

    @FunctionalInterface
    public interface BlockAtlasStitched {
        void onBlockAtlasStitched(TextureAtlas atlas);
    }

    @FunctionalInterface
    public interface LevelChanged {
        void onLevelChanged(ClientLevel level);
    }

    @FunctionalInterface
    public interface WorldLeft {
        void onWorldLeft();
    }
}
