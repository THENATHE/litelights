package com.thenathe.litelights.render.feature.farterrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * FarSnow's lines as 0.9.10 kept them, without the GPU, the colours and the resumed and forming
 * changes: the background fill only read Almanac's lines, and the render thread merged them into
 * the rings, settled finished changes and encoded every upload (FarSnow.apply, applyPart, upload
 * and updateProgress, copied as they were). The reference FarSnowFillTest holds the new fill to.
 * Fills run as soon as they start, as with a synchronous executor.
 */
final class OldFarSnowLines {
    record Upload(int minX, int minZ, int maxX, int maxZ, int row, byte[] texels) {
    }

    private record Part(int originX, int originZ, OldFarSnowGrid.Rect rect, boolean move) {
    }

    private static final class Job {
        boolean change;
        @Nullable Part inner;
        @Nullable Part outer;
        int @Nullable [] innerLines;
        int @Nullable [] outerLines;
        long changeStart = -1;
        long changeTicks = -1;
    }

    final OldFarSnowGrid inner = new OldFarSnowGrid(FarSnow.INNER_SPACING, 1024);
    final OldFarSnowGrid outer = new OldFarSnowGrid(FarSnow.OUTER_SPACING, 4096);
    final List<Upload> uploads = new ArrayList<>();
    private final FarSnowSource source;
    private final int floor;
    private final ByteBuffer buffer = ByteBuffer.allocate(OldFarSnowGrid.SIZE * OldFarSnowGrid.SIZE * 4).order(ByteOrder.LITTLE_ENDIAN);
    private @Nullable Job job;
    private boolean changeWaiting;
    private long pendingChangeStart = -1;
    private boolean changeRunning;
    private long changeStart;
    private long changeTicks = FarSnow.DEFAULT_CHANGE_TICKS;
    float progress = 1.0f;
    int changes;

    OldFarSnowLines(FarSnowSource source, int floor) {
        this.source = source;
        this.floor = floor;
    }

    void changed(FarSnowSource.Area area, FarSnowSource.Kind kind) {
        if (kind == FarSnowSource.Kind.SEASON) {
            pendingChangeStart = source.changedTick(null);
            changeWaiting = true;
            inner.markAllDirty();
            outer.markAllDirty();
        } else {
            inner.markDirty(area);
            outer.markDirty(area);
        }
    }

    /** One frame, in 0.9.10's order: take finished fills in, start the next, then the change's progress. */
    void frame(double cameraX, double cameraZ, long gameTime) {
        if (job != null) {
            apply(job);
            job = null;
        }
        startJobs(cameraX, cameraZ);
        updateProgress(gameTime);
    }

    private void startJobs(double cameraX, double cameraZ) {
        boolean change = changeWaiting;
        Job next = new Job();
        next.change = change;
        if (change) {
            changeWaiting = false;
            next.changeStart = pendingChangeStart;
            next.changeTicks = source.thawTicks(null);
        }
        next.inner = plan(inner, cameraX, cameraZ);
        next.outer = plan(outer, cameraX, cameraZ);
        if (next.inner != null || next.outer != null || change) {
            next.innerLines = fill(next.inner, FarSnow.INNER_SPACING);
            next.outerLines = fill(next.outer, FarSnow.OUTER_SPACING);
            job = next;
        }
    }

    private static @Nullable Part plan(OldFarSnowGrid grid, double cameraX, double cameraZ) {
        if (grid.wantsMove(cameraX, cameraZ)) {
            grid.takeDirty();
            return new Part(grid.wantedOriginX(cameraX), grid.wantedOriginZ(cameraZ), OldFarSnowGrid.Rect.ALL, true);
        }
        OldFarSnowGrid.Rect rect = grid.takeDirty();
        return rect == null ? null : new Part(grid.originX(), grid.originZ(), rect, false);
    }

    private int @Nullable [] fill(@Nullable Part part, int spacing) {
        if (part == null) {
            return null;
        }
        OldFarSnowGrid.Rect rect = part.rect();
        int[] lines = new int[rect.width() * rect.height()];
        source.fillLines(null, part.originX() + spacing / 2 + rect.minX() * spacing, part.originZ() + spacing / 2 + rect.minZ() * spacing,
            spacing, rect.width(), rect.height(), lines);
        return lines;
    }

    private void apply(Job done) {
        int changing = done.change ? changesIn(inner, done.inner, done.innerLines) + changesIn(outer, done.outer, done.outerLines) : 0;
        if (changing == 0) {
            applyPart(inner, done.inner, done.innerLines, false, 0);
            applyPart(outer, done.outer, done.outerLines, false, OldFarSnowGrid.SIZE);
        } else {
            if (changeRunning) {
                inner.settle();
                outer.settle();
            }
            applyPart(inner, done.inner, done.innerLines, true, 0);
            applyPart(outer, done.outer, done.outerLines, true, OldFarSnowGrid.SIZE);
            changes++;
            changeRunning = true;
            changeStart = done.changeStart >= 0 ? done.changeStart : 0L;
            changeTicks = done.changeTicks > 0 ? done.changeTicks : FarSnow.DEFAULT_CHANGE_TICKS;
            progress = 0.0f;
        }
    }

    private static int changesIn(OldFarSnowGrid grid, @Nullable Part part, int @Nullable [] lines) {
        return part == null || lines == null || part.move ? 0 : grid.countChanges(part.rect, lines);
    }

    private void applyPart(OldFarSnowGrid grid, @Nullable Part part, int @Nullable [] lines, boolean change, int row) {
        if (lines != null) {
            if (part.move) {
                grid.moveTo(part.originX, part.originZ, lines, changeRunning);
            } else if (change) {
                grid.acceptChange(part.rect, lines);
            } else {
                grid.accept(part.rect, lines);
            }
        }
        if (change || lines != null) {
            upload(grid, change || part == null || part.move ? OldFarSnowGrid.Rect.ALL : part.rect, row);
        }
    }

    private void upload(OldFarSnowGrid grid, OldFarSnowGrid.Rect rect, int row) {
        buffer.clear();
        grid.encode(rect, floor, buffer);
        byte[] texels = new byte[buffer.limit()];
        buffer.get(0, texels);
        uploads.add(new Upload(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ(), row, texels));
    }

    private void updateProgress(double now) {
        if (!changeRunning) {
            return;
        }
        progress = (float) FarSnow.changeProgress(Math.max(0.0, now - changeStart), changeTicks, false, 0.0);
        if (progress >= 1.0f) {
            changeRunning = false;
            inner.settle();
            outer.settle();
        }
    }
}
