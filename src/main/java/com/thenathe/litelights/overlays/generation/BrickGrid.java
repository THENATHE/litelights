package com.thenathe.litelights.overlays.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The brick pattern of a texture: its courses (rows of bricks) and, in each course, where the
 * vertical joints are. The Bricks edge style cuts overlays along these mortar lines, so an
 * overlay always ends on a whole brick.
 *
 * <p>The pattern is read from the texture itself, so a resource pack's own bricks are followed:
 * <ol>
 *   <li>Horizontal mortar lines are rows where the colour changes sharply from the row next to
 *       them, all the way across. Each such change is a candidate edge of a mortar line; the line
 *       itself is the darkest (or, for light mortar like vanilla's bricks, the lightest) run of rows
 *       beside it, one sixteenth of a block thick.</li>
 *   <li>Between two lines lies a course. Its joints are found the same way, column by column,
 *       over the course's rows, and kept only where their colour is closer to the mortar's than to
 *       the bricks'. That drops cracks, moss and the light bevel that vanilla draws on the other
 *       side of each joint.</li>
 *   <li>Dark and light mortar are both tried, and the reading whose mortar texels are the most
 *       alike in colour wins. A reading counts only if it has at least two courses, every course
 *       has a joint, the joints move from course to course (a running bond, not a plain grid of
 *       tiles) and the mortar is clearly of one colour.</li>
 * </ol>
 * When no reading counts (a cracked texture whose cracks cut the mortar, an irregular pattern, or
 * a plain block given the Bricks style), one of two patterns measured from vanilla is used instead:
 * bricks (courses a quarter of a block high) or stone bricks (half a block), whichever is nearer
 * the course height the texture seems to have. See DECISIONS, overlays17, for the measurements.
 *
 * <p>Each course owns the mortar line below it, and each brick the joint at its right end, so the
 * texture is divided into whole bricks with nothing left over.
 */
public final class BrickGrid {
    /** Where the pattern came from. */
    public enum Origin {
        DETECTED,
        /** Vanilla's bricks: courses of 4 sixteenths, bricks of 8, every other course offset by half a brick. */
        BRICKS_PRESET,
        /** Vanilla's stone bricks: courses of 8 sixteenths, bricks of 16, offset by half a brick. */
        STONE_BRICKS_PRESET
    }

    /** A colour change weaker than this (RGB distance) is texture noise, never a mortar edge. */
    private static final double EDGE_FLOOR = 20;
    /** A mortar edge is at least this share of the texture's strongest edge. */
    private static final double EDGE_SHARE = 0.45;
    /** How alike the mortar texels must be, as their colour variance over the whole texture's. */
    private static final double MAX_MORTAR_SPREAD = 0.6;

    /**
     * One brick, or the part of one that lies inside the texture. A brick that crosses the
     * texture's border carries on in the next block, and says so, so the blocks on either side can
     * make the same choice about it.
     *
     * @param course index of its course, counted from the top of the texture
     */
    public record Brick(int id, int course, int left, int right, int top, int bottom, boolean continuesLeft,
                        boolean continuesRight, boolean continuesUp, boolean continuesDown) {
        public boolean overlapsColumns(int fromX, int toX) {
            return left <= toX && right >= fromX;
        }
    }

    /** A run of mortar rows or columns, which may wrap past the end of the texture. */
    private record Line(int start, int length) {
    }

    /** A course: its body rows, the mortar line under them, and its joints. */
    private record Course(Line body, Line mortar, List<Line> joints) {
    }

    private final int size;
    private final Origin origin;
    private final List<Course> courses;
    private final boolean[] mortar;
    private final int[] brickAt;
    private final List<Brick> bricks = new ArrayList<>();
    /** Bricks by course row, top to bottom, each row left to right. */
    private final List<List<Brick>> rows = new ArrayList<>();

    private BrickGrid(int size, Origin origin, List<Course> courses) {
        this.size = size;
        this.origin = origin;
        // Top course first, so two readings of one pattern compare equal however they were found.
        List<Course> sorted = new ArrayList<>(courses);
        sorted.sort((first, second) -> Integer.compare(first.body().start(), second.body().start()));
        this.courses = List.copyOf(sorted);
        this.mortar = new boolean[size * size];
        this.brickAt = new int[size * size];
        divide();
    }

    /** The pattern of a texture, read from it, or the nearest vanilla pattern when it can't be read. */
    public static BrickGrid of(SourceTexture texture) {
        BrickGrid detected = detect(texture.pixels(), texture.size());
        if (detected != null) {
            return detected;
        }
        return preset(texture.size(), nearestPreset(texture.pixels(), texture.size()));
    }

    /** The pattern read from the texels, or {@code null} if no reading counts. */
    static BrickGrid detect(int[] pixels, int size) {
        for (int texel : pixels) {
            if ((texel >>> 24) < 128) {
                return null;
            }
        }
        Reading best = null;
        for (boolean darkMortar : new boolean[] {true, false}) {
            Reading reading = read(pixels, size, darkMortar);
            if (reading != null && (best == null || reading.spread < best.spread)) {
                best = reading;
            }
        }
        return best == null ? null : new BrickGrid(size, Origin.DETECTED, best.courses);
    }

    /** One of the two measured vanilla patterns, drawn at the given texture size. */
    public static BrickGrid preset(int size, Origin pattern) {
        double scale = size / 16.0;
        int thickness = Math.max(1, (int) Math.round(scale));
        boolean bricks = pattern == Origin.BRICKS_PRESET;
        int courseCount = bricks ? 4 : 2;
        // Joint columns at 16x for even and odd courses: the mortar column ends each brick.
        int[][] joints = bricks ? new int[][] {{3, 11}, {7, 15}} : new int[][] {{15}, {7}};
        List<Course> courses = new ArrayList<>();
        int courseHeight = 16 / courseCount;
        for (int course = 0; course < courseCount; course++) {
            int top = (int) Math.round(course * courseHeight * scale);
            int lineEnd = (int) Math.round((course + 1) * courseHeight * scale);
            List<Line> courseJoints = new ArrayList<>();
            for (int joint : joints[course % 2]) {
                courseJoints.add(new Line(Math.floorMod((int) Math.round((joint + 1) * scale) - thickness, size), thickness));
            }
            courses.add(new Course(new Line(top, lineEnd - thickness - top), new Line(lineEnd - thickness, thickness),
                courseJoints));
        }
        return new BrickGrid(size, pattern, courses);
    }

    public int size() {
        return size;
    }

    public Origin origin() {
        return origin;
    }

    public List<Brick> bricks() {
        return bricks;
    }

    /** The bricks of each course row inside the texture, top to bottom, each left to right. */
    public List<List<Brick>> rows() {
        return rows;
    }

    public boolean isMortar(int x, int y) {
        return mortar[y * size + x];
    }

    /** The brick a texel belongs to, its mortar included. */
    public Brick brickAt(int x, int y) {
        return bricks.get(brickAt[y * size + x]);
    }

    /** Course heights and joint columns, for logs and tests: {@code 8:15 8:7} is vanilla's stone bricks. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Course course : courses) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(course.body().length() + course.mortar().length()).append(':');
            for (int i = 0; i < course.joints().size(); i++) {
                Line joint = course.joints().get(i);
                text.append(i == 0 ? "" : ",").append(Math.floorMod(joint.start() + joint.length() - 1, size));
            }
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BrickGrid grid && grid.size == size && grid.courses.equals(courses);
    }

    @Override
    public int hashCode() {
        return courses.hashCode() * 31 + size;
    }

    // Dividing the texture into bricks

    private void divide() {
        int nextId = 0;
        for (int index = 0; index < courses.size(); index++) {
            Course course = courses.get(index);
            int courseTop = course.body().start();
            int courseLength = course.body().length() + course.mortar().length();
            for (int k = 0; k < course.mortar().length(); k++) {
                int y = Math.floorMod(course.mortar().start() + k, size);
                for (int x = 0; x < size; x++) {
                    mortar[y * size + x] = true;
                }
            }
            for (int k = 0; k < course.body().length(); k++) {
                int y = Math.floorMod(course.body().start() + k, size);
                for (Line joint : course.joints()) {
                    for (int j = 0; j < joint.length(); j++) {
                        mortar[y * size + Math.floorMod(joint.start() + j, size)] = true;
                    }
                }
            }
            for (int[] rowPart : split(courseTop, courseLength)) {
                List<Brick> row = new ArrayList<>();
                List<Line> joints = course.joints();
                for (int j = 0; j < joints.size(); j++) {
                    Line previous = joints.get(Math.floorMod(j - 1, joints.size()));
                    Line own = joints.get(j);
                    int start = Math.floorMod(previous.start() + previous.length(), size);
                    int end = Math.floorMod(own.start() + own.length() - 1, size);
                    int length = Math.floorMod(end - start, size) + 1;
                    for (int[] columnPart : split(start, length)) {
                        Brick brick = new Brick(nextId++, index, columnPart[0], columnPart[1], rowPart[0], rowPart[1],
                            columnPart[2] == 1, columnPart[3] == 1, rowPart[2] == 1, rowPart[3] == 1);
                        bricks.add(brick);
                        row.add(brick);
                        for (int y = brick.top(); y <= brick.bottom(); y++) {
                            for (int x = brick.left(); x <= brick.right(); x++) {
                                brickAt[y * size + x] = brick.id();
                            }
                        }
                    }
                }
                row.sort((first, second) -> Integer.compare(first.left(), second.left()));
                rows.add(row);
            }
        }
        rows.sort((first, second) -> Integer.compare(first.getFirst().top(), second.getFirst().top()));
    }

    /**
     * A run of {@code length} rows or columns from {@code start}, cut at the texture's border:
     * {from, to, carries on before, carries on after} for each part.
     */
    private List<int[]> split(int start, int length) {
        if (length >= size && start == 0) {
            return List.of(new int[] {0, size - 1, 0, 0});
        }
        int end = start + length - 1;
        if (end < size) {
            return List.of(new int[] {start, end, 0, 0});
        }
        return List.of(new int[] {0, end - size, 1, 0}, new int[] {start, size - 1, 0, 1});
    }

    // Reading the pattern

    private record Reading(List<Course> courses, double spread) {
    }

    private static Reading read(int[] pixels, int size, boolean darkMortar) {
        int thickness = Math.max(1, (int) Math.round(size / 16.0));
        double[] rowEdges = new double[size];
        double[] rowLight = new double[size];
        for (int y = 0; y < size; y++) {
            double[] changes = new double[size];
            for (int x = 0; x < size; x++) {
                changes[x] = distance(pixels[y * size + x], pixels[(y + 1) % size * size + x]);
                rowLight[y] += luminance(pixels[y * size + x]) / size;
            }
            rowEdges[y] = median(changes);
        }
        List<Line> lines = lines(rowEdges, rowLight, size, thickness, darkMortar);
        if (lines.size() < 2) {
            return null;
        }
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line above = lines.get(i);
            Line below = lines.get((i + 1) % lines.size());
            int bodyStart = Math.floorMod(above.start() + above.length(), size);
            int bodyLength = Math.floorMod(below.start() - bodyStart, size);
            if (bodyLength < 1) {
                return null;
            }
            Line body = new Line(bodyStart, bodyLength);
            List<Line> joints = joints(pixels, size, thickness, body, below, darkMortar);
            if (joints.isEmpty()) {
                return null;
            }
            courses.add(new Course(body, below, joints));
        }
        TreeSet<String> patterns = new TreeSet<>();
        courses.forEach(course -> patterns.add(course.joints().toString()));
        if (patterns.size() < 2) {
            // Every course has its joints in the same place: a plain grid of tiles or panels.
            return null;
        }
        double spread = mortarSpread(pixels, size, courses);
        return spread < MAX_MORTAR_SPREAD ? new Reading(courses, spread) : null;
    }

    /** The joints of one course, read over its body rows; only those coloured like its mortar line. */
    private static List<Line> joints(int[] pixels, int size, int thickness, Line body, Line mortarLine, boolean darkMortar) {
        double[] columnEdges = new double[size];
        double[] columnLight = new double[size];
        double[] changes = new double[body.length()];
        for (int x = 0; x < size; x++) {
            for (int k = 0; k < body.length(); k++) {
                int y = Math.floorMod(body.start() + k, size);
                changes[k] = distance(pixels[y * size + x], pixels[y * size + (x + 1) % size]);
                columnLight[x] += luminance(pixels[y * size + x]) / body.length();
            }
            columnEdges[x] = lowerMedian(changes);
        }
        double[] mortarColour = meanColour(pixels, size, mortarLine, new Line(0, size));
        double[] bodyColour = meanColour(pixels, size, body, new Line(0, size));
        List<Line> joints = new ArrayList<>();
        for (Line joint : lines(columnEdges, columnLight, size, thickness, darkMortar)) {
            double[] colour = meanColour(pixels, size, body, joint);
            if (colourDistance(colour, mortarColour) < colourDistance(colour, bodyColour)) {
                joints.add(joint);
            }
        }
        return joints;
    }

    /**
     * Mortar lines along one axis. {@code edges[i]} is how sharply the colour changes between
     * position i and i + 1, {@code light[i]} how bright position i is. Strong edges close together
     * belong to one line; the line is the darkest (or lightest) run of {@code thickness} positions
     * touching them.
     */
    private static List<Line> lines(double[] edges, double[] light, int size, int thickness, boolean darkMortar) {
        double strongest = Arrays.stream(edges).max().orElse(0);
        double threshold = Math.max(EDGE_FLOOR, EDGE_SHARE * strongest);
        List<Integer> strong = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (edges[i] >= threshold) {
                strong.add(i);
            }
        }
        if (strong.isEmpty()) {
            return List.of();
        }
        // Start the groups after a gap, so a group that wraps past the end isn't split in two.
        int first = -1;
        for (int i = 0; i < strong.size(); i++) {
            int previous = strong.get(Math.floorMod(i - 1, strong.size()));
            if (strong.size() == 1 || Math.floorMod(strong.get(i) - previous, size) > thickness) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return List.of();
        }
        boolean[] covered = new boolean[size];
        int groupStart = strong.get(first);
        int groupEnd = groupStart;
        for (int i = 1; i <= strong.size(); i++) {
            int edge = strong.get((first + i) % strong.size());
            boolean last = i == strong.size();
            if (!last && Math.floorMod(edge - groupEnd, size) <= thickness) {
                groupEnd = edge;
                continue;
            }
            markLine(covered, light, size, thickness, groupStart, groupEnd, darkMortar);
            groupStart = edge;
            groupEnd = edge;
        }
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (covered[i] && !covered[Math.floorMod(i - 1, size)]) {
                int length = 0;
                while (length < size && covered[(i + length) % size]) {
                    length++;
                }
                lines.add(new Line(i, length));
            }
        }
        if (lines.isEmpty() && covered[0]) {
            return List.of();
        }
        return lines;
    }

    /** Marks the darkest or lightest run of positions touching the edges from {@code groupStart} to {@code groupEnd}. */
    private static void markLine(boolean[] covered, double[] light, int size, int thickness, int groupStart, int groupEnd,
                                 boolean darkMortar) {
        int span = Math.floorMod(groupEnd - groupStart, size);
        int bestStart = groupStart;
        double best = Double.NaN;
        for (int start = groupStart - thickness + 1; start <= groupStart + span + 1; start++) {
            double total = 0;
            for (int k = 0; k < thickness; k++) {
                total += light[Math.floorMod(start + k, size)];
            }
            if (Double.isNaN(best) || (darkMortar ? total < best : total > best)) {
                best = total;
                bestStart = start;
            }
        }
        for (int k = 0; k < thickness; k++) {
            covered[Math.floorMod(bestStart + k, size)] = true;
        }
    }

    /** How alike the mortar texels' colours are, as their variance over the whole texture's. */
    private static double mortarSpread(int[] pixels, int size, List<Course> courses) {
        List<Integer> texels = new ArrayList<>();
        for (Course course : courses) {
            for (int k = 0; k < course.mortar().length(); k++) {
                int y = Math.floorMod(course.mortar().start() + k, size);
                for (int x = 0; x < size; x++) {
                    texels.add(pixels[y * size + x]);
                }
            }
            for (Line joint : course.joints()) {
                for (int k = 0; k < course.body().length(); k++) {
                    int y = Math.floorMod(course.body().start() + k, size);
                    for (int j = 0; j < joint.length(); j++) {
                        texels.add(pixels[y * size + Math.floorMod(joint.start() + j, size)]);
                    }
                }
            }
        }
        List<Integer> all = new ArrayList<>(pixels.length);
        for (int texel : pixels) {
            all.add(texel);
        }
        double overall = variance(all);
        return overall <= 0 ? Double.MAX_VALUE : variance(texels) / overall;
    }

    /** The course height the texture seems to have, for picking a preset when its pattern can't be read. */
    private static Origin nearestPreset(int[] pixels, int size) {
        double[] rowEdges = new double[size];
        double[] rowLight = new double[size];
        for (int y = 0; y < size; y++) {
            double[] changes = new double[size];
            for (int x = 0; x < size; x++) {
                changes[x] = distance(pixels[y * size + x], pixels[(y + 1) % size * size + x]);
                rowLight[y] += luminance(pixels[y * size + x]) / size;
            }
            rowEdges[y] = median(changes);
        }
        int thickness = Math.max(1, (int) Math.round(size / 16.0));
        List<Line> lines = lines(rowEdges, rowLight, size, thickness, true);
        if (lines.size() < 2) {
            return Origin.STONE_BRICKS_PRESET;
        }
        double courseHeight = 16.0 / lines.size();
        return Math.abs(courseHeight - 4) < Math.abs(courseHeight - 8) ? Origin.BRICKS_PRESET : Origin.STONE_BRICKS_PRESET;
    }

    private static double[] meanColour(int[] pixels, int size, Line rows, Line columns) {
        double[] total = new double[3];
        int count = 0;
        for (int k = 0; k < rows.length(); k++) {
            int y = Math.floorMod(rows.start() + k, size);
            for (int j = 0; j < columns.length(); j++) {
                int texel = pixels[y * size + Math.floorMod(columns.start() + j, size)];
                total[0] += (texel >> 16) & 0xFF;
                total[1] += (texel >> 8) & 0xFF;
                total[2] += texel & 0xFF;
                count++;
            }
        }
        for (int channel = 0; channel < 3; channel++) {
            total[channel] /= Math.max(1, count);
        }
        return total;
    }

    private static double variance(List<Integer> texels) {
        double[] mean = new double[3];
        for (int texel : texels) {
            mean[0] += (texel >> 16) & 0xFF;
            mean[1] += (texel >> 8) & 0xFF;
            mean[2] += texel & 0xFF;
        }
        for (int channel = 0; channel < 3; channel++) {
            mean[channel] /= texels.size();
        }
        double total = 0;
        for (int texel : texels) {
            double red = ((texel >> 16) & 0xFF) - mean[0];
            double green = ((texel >> 8) & 0xFF) - mean[1];
            double blue = (texel & 0xFF) - mean[2];
            total += red * red + green * green + blue * blue;
        }
        return total / texels.size();
    }

    private static double distance(int first, int second) {
        double red = ((first >> 16) & 0xFF) - ((second >> 16) & 0xFF);
        double green = ((first >> 8) & 0xFF) - ((second >> 8) & 0xFF);
        double blue = (first & 0xFF) - (second & 0xFF);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static double colourDistance(double[] first, double[] second) {
        double red = first[0] - second[0];
        double green = first[1] - second[1];
        double blue = first[2] - second[2];
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static double luminance(int texel) {
        return 0.299 * ((texel >> 16) & 0xFF) + 0.587 * ((texel >> 8) & 0xFF) + 0.114 * (texel & 0xFF);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /** The lower of the middle values: an edge this strong shows in at least half the rows. */
    private static double lowerMedian(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
    }
}
