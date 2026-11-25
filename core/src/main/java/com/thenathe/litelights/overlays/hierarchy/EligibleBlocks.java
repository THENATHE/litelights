package com.thenathe.litelights.overlays.hierarchy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The blocks overlays can work with: plain, opaque, full cubes.
 *
 * <p>Continuity only draws an overlay on a face that covers the whole side of the block, so slabs,
 * stairs and other partial shapes can't take part. It also only takes an overlay from a neighbour
 * whose collision box is a full cube, which is why mud and soul sand (you sink into them) can
 * receive overlays but never spill their own. Blocks with a block entity, an action on use, or
 * state that changes how they look (facing, lit, power) are left out too: they're furniture and
 * machines, not terrain.
 */
public final class EligibleBlocks {
    /** State properties that don't change which textures a block shows on its faces. */
    private static final Set<String> HARMLESS_PROPERTIES = Set.of("axis", "snowy");

    private final Set<String> ids;
    private final Set<String> spillable;

    private EligibleBlocks(Set<String> ids, Set<String> spillable) {
        this.ids = Collections.unmodifiableSet(ids);
        this.spillable = Collections.unmodifiableSet(spillable);
    }

    /** Scans the block registry. Call it on the client thread. */
    public static EligibleBlocks scan() {
        Set<String> ids = new TreeSet<>();
        Set<String> spillable = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (isPlainFullCube(block)) {
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                ids.add(id);
                if (block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                    spillable.add(id);
                }
            }
        }
        return new EligibleBlocks(ids, spillable);
    }

    /** A fixed list, for building a plan without the game's block registry (tests and probes). */
    public static EligibleBlocks of(Collection<String> ids, Collection<String> spillable) {
        return new EligibleBlocks(new TreeSet<>(ids), new TreeSet<>(spillable));
    }

    public boolean isEligible(String blockId) {
        return ids.contains(blockId);
    }

    /** Whether Continuity will take an overlay from this block, which needs a full collision box. */
    public boolean canSpill(String blockId) {
        return spillable.contains(blockId);
    }

    public List<String> all() {
        return new ArrayList<>(ids);
    }

    private static boolean isPlainFullCube(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.hasBlockEntity() || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        // Water, lava and anything that holds a fluid are never sources or receivers. They're
        // already left out as non-cubes; this keeps a modded full block with a fluid out too.
        if (block instanceof LiquidBlock || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.isSolidRender()) {
            return false;
        }
        for (Property<?> property : state.getProperties()) {
            if (!HARMLESS_PROPERTIES.contains(property.getName())) {
                return false;
            }
        }
        return !reactsToUse(block.getClass());
    }

    /** True when the block's class (or a parent below the base block) overrides one of the use handlers. */
    private static boolean reactsToUse(Class<?> type) {
        for (Class<?> current = type; current != null && current != Block.class && current != BlockBehaviour.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if (name.equals("useWithoutItem") || name.equals("useItemOn")) {
                    return true;
                }
            }
        }
        return false;
    }
}
