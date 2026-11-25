package com.thenathe.litelights.clouds.field;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Pictures of the cloud field with no game running, so its look can be judged before anything is
 * drawn in game: maps from straight above of a wide area in clear weather, rain and thunder, near
 * maps of single clouds, side slices showing heights, and sequences over time. Runs only when
 * {@code -Plitelights.clouds.preview=<folder>} is given to Gradle; writes PNGs there. Every picture
 * carries a scale bar in blocks, the tick and the weather.
 */
class CloudFieldPictures {
    static final long SEED = 0x51CE5EEDL;
    static final float BASE = 192.0f;
    /** A day and a quarter into the world: every lattice slot around has formations of all ages. */
    static final double TICK = 30_000.0;
    static final double CENTRE_X = 0.0;
    static final double CENTRE_Z = 0.0;
    static final double REACH = 8_192.0;

    private static final Color GROUND = new Color(0x26, 0x33, 0x3B);
    private static final Color LABEL = new Color(0xF2, 0xF2, 0xF2);
    private static final Color LABEL_SHADOW = new Color(0, 0, 0, 170);

    @Test
    void draw() throws IOException {
        String folder = System.getProperty("litelights.clouds.preview", "");
        Assumptions.assumeFalse(folder.isBlank());
        File out = new File(folder);
        out.mkdirs();
        for (Weather weather : Weather.values()) {
            CloudField field = field(TICK, weather.rain, weather.thunder);
            write(out, "map-" + weather.file, map(field, TICK, weather.label, CENTRE_X, CENTRE_Z, REACH, 1_000, false));
            write(out, "slice-" + weather.file, weather == Weather.THUNDER
                ? stormSlice(field, TICK, weather.label, REACH)
                : slice(field, TICK, weather.label, CENTRE_X, CENTRE_Z, REACH, "through the camera"));
        }
        write(out, "types-clear", map(field(TICK, 0.0f, 0.0f), TICK, Weather.CLEAR.label, CENTRE_X, CENTRE_Z, REACH, 1_000, true));
        write(out, "types-thunder", map(field(TICK, 1.0f, 1.0f), TICK, Weather.THUNDER.label, CENTRE_X, CENTRE_Z, REACH, 1_000, true));

        write(out, "near-clear", map(field(TICK, 0.0f, 0.0f), TICK, Weather.CLEAR.label, CENTRE_X, CENTRE_Z, 1_536.0, 1_000, false));
        for (CloudType type : new CloudType[] {CloudType.CUMULUS, CloudType.CONGESTUS}) {
            // The first time from TICK on, in steps of five minutes, that a grown formation of the type is near.
            for (double tick = TICK; tick < TICK + 72_000.0; tick += 6_000.0) {
                CloudField clear = field(tick, 0.0f, 0.0f);
                CloudFormation formation = biggestCloudsNear(clear, tick, type);
                if (formation != null) {
                    double[] tallest = tallestCloud(clear, tick, formation);
                    String label = "Clear, a " + type.id() + " formation";
                    write(out, "near-" + type.id(), map(clear, tick, label, tallest[0], tallest[1], 1_536.0, 1_000, false));
                    write(out, "near-slice-" + type.id(), slice(clear, tick, label, tallest[0], tallest[1], 1_000.0,
                        "through the formation's tallest " + type.id()));
                    break;
                }
            }
        }
        write(out, "sequence-formations", formationSequence());
        write(out, "sequence-clouds", cloudSequence());
        write(out, "sequence-rain", rainSequence());
    }

    enum Weather {
        CLEAR("clear", "Clear", 0.0f, 0.0f),
        RAIN("rain", "Rain", 1.0f, 0.0f),
        THUNDER("thunder", "Thunder", 1.0f, 1.0f);

        final String file;
        final String label;
        final float rain;
        final float thunder;

        Weather(String file, String label, float rain, float thunder) {
            this.file = file;
            this.label = label;
            this.rain = rain;
            this.thunder = thunder;
        }
    }

    static CloudField field(double tick, float rain, float thunder) {
        return new CloudField(VanillaWeatherSky.sky(SEED, BASE, tick, rain, thunder, CENTRE_X, CENTRE_Z, REACH));
    }

    /** The formation of this type, grown, within 7 km of the camera, with the largest single clouds. */
    private static @Nullable CloudFormation biggestCloudsNear(CloudField field, double tick, CloudType type) {
        CloudFormation best = null;
        for (CloudFormation formation : field.sky().formations()) {
            double dx = formation.centreXAt(tick) - CENTRE_X;
            double dz = formation.centreZAt(tick) - CENTRE_Z;
            if (formation.type() != type || formation.lifeAt(tick) < 0.8f || dx * dx + dz * dz > 7_000.0 * 7_000.0) {
                continue;
            }
            if (best == null || formation.sizeScale() > best.sizeScale()) {
                best = formation;
            }
        }
        return best;
    }

    /** Where the formation's tallest cloud of its own type stands, world x and z. */
    private static double[] tallestCloud(CloudField field, double tick, CloudFormation formation) {
        double centreX = formation.centreXAt(tick);
        double centreZ = formation.centreZAt(tick);
        double half = formation.radius();
        double[] tallest = {centreX, centreZ};
        float tallestTop = Float.NEGATIVE_INFINITY;
        CloudColumn column = new CloudColumn();
        for (double z = centreZ - half; z <= centreZ + half; z += 16.0) {
            for (double x = centreX - half; x <= centreX + half; x += 16.0) {
                field.sampleWorld(tick, x, z, 16.0, column);
                for (int span = 0; span < column.spanCount(); span++) {
                    if (column.type(span) == formation.type() && column.top(span) > tallestTop) {
                        tallestTop = column.top(span);
                        tallest = new double[] {x, z};
                    }
                }
            }
        }
        return tallest;
    }

    /** Wide formations drifting, growing and ending: 16 km across, a frame every 4,000 ticks. */
    private static BufferedImage formationSequence() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int frame = 0; frame < 12; frame++) {
            double tick = TICK + frame * 4_000.0;
            frames.add(map(field(tick, 0.0f, 0.0f), tick, "Clear", CENTRE_X, CENTRE_Z, REACH, 500, false));
        }
        return sheet(frames, 4);
    }

    /** Single clouds growing and fading: 2 km across, a frame every 600 ticks (30 seconds). */
    private static BufferedImage cloudSequence() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int frame = 0; frame < 12; frame++) {
            double tick = TICK + frame * 600.0;
            frames.add(map(field(tick, 0.0f, 0.0f), tick, "Clear", CENTRE_X, CENTRE_Z, 1_024.0, 500, false));
        }
        return sheet(frames, 4);
    }

    /**
     * Vanilla's rain starting at the first frame and stopping after 3,600 ticks, through
     * {@link CloudWeather}: the deck closing, then breaking up. 16 km across, a frame every 600 ticks.
     */
    private static BufferedImage rainSequence() {
        List<BufferedImage> frames = new ArrayList<>();
        CloudWeather weather = new CloudWeather();
        weather.tick(0.0f, 0.0f);
        for (int tick = 0; tick <= 6_600; tick++) {
            boolean raining = tick < 3_600;
            // Vanilla's own levels move 0.01 a tick.
            float vanillaRain = raining ? Math.min(1.0f, tick * 0.01f) : Math.max(0.0f, 1.0f - (tick - 3_600) * 0.01f);
            weather.tick(vanillaRain, 0.0f);
            if (tick % 600 == 0) {
                double at = TICK + tick;
                String label = String.format(Locale.ROOT, "Vanilla rain %s, clouds' rain %.2f", raining ? "on" : "off", weather.rain());
                frames.add(map(field(at, weather.rain(), weather.thunder()), at, label, CENTRE_X, CENTRE_Z, REACH, 500, false));
            }
        }
        return sheet(frames, 4);
    }

    /**
     * A map from straight above, {@code 2 * half} blocks across around a centre. Clouds are white
     * over dark ground, shaded by a low sun from the north-west across their tops, greyer where
     * stormy and bluer the higher they are; with {@code types} each type has its own colour and the
     * formations' areas are outlined.
     */
    static BufferedImage map(CloudField field, double tick, String weather, double centreX, double centreZ,
                             double half, int pixels, boolean types) {
        double blocksPerPixel = half * 2.0 / pixels;
        double driftX = field.driftX(tick);
        double driftZ = field.driftZ(tick);
        CloudField.Area area = field.area(tick, centreX - half - driftX, centreZ - half - driftZ,
            centreX + half - driftX, centreZ + half - driftZ);
        CloudColumn column = new CloudColumn();
        float[] tops = new float[pixels * pixels];
        float[] covers = new float[pixels * pixels];
        float[] storms = new float[pixels * pixels];
        CloudType[] kinds = new CloudType[pixels * pixels];
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                double worldX = centreX - half + (px + 0.5) * blocksPerPixel;
                double worldZ = centreZ - half + (py + 0.5) * blocksPerPixel;
                area.column(worldX - driftX, worldZ - driftZ, blocksPerPixel, column);
                int index = px + py * pixels;
                if (column.isClear()) {
                    tops[index] = Float.NaN;
                    continue;
                }
                int shown = seenFromAbove(column);
                tops[index] = column.top(shown);
                covers[index] = column.cover();
                storms[index] = column.storminess(shown);
                kinds[index] = column.type(shown);
            }
        }
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int index = px + py * pixels;
                float top = tops[index];
                if (Float.isNaN(top)) {
                    image.setRGB(px, py, GROUND.getRGB());
                    continue;
                }
                // Relief from the tops' slope, lit from the north-west (up-left).
                float west = px > 0 && !Float.isNaN(tops[index - 1]) ? tops[index - 1] : BASE;
                float north = py > 0 && !Float.isNaN(tops[index - pixels]) ? tops[index - pixels] : BASE;
                double slope = ((top - west) + (top - north)) / blocksPerPixel;
                double light = Math.clamp(0.82 + 0.35 * Math.tanh(slope * 0.35), 0.45, 1.0);
                Color colour = types ? typeColour(kinds[index]) : cloudColour(top, storms[index]);
                double opacity = Math.clamp(covers[index] * 1.4, 0.0, 1.0);
                image.setRGB(px, py, mix(GROUND, scale(colour, light), opacity).getRGB());
            }
        }
        Graphics2D graphics = graphics(image);
        if (types) {
            outlines(graphics, field, tick, centreX, centreZ, half, blocksPerPixel);
            legend(graphics, pixels);
        }
        // The reach, around the camera.
        double reach = REACH / blocksPerPixel;
        if (reach < pixels) {
            graphics.setColor(new Color(255, 255, 255, 70));
            graphics.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[] {6.0f, 6.0f}, 0.0f));
            double cameraX = (CENTRE_X - centreX + half) / blocksPerPixel;
            double cameraZ = (CENTRE_Z - centreZ + half) / blocksPerPixel;
            graphics.draw(new Ellipse2D.Double(cameraX - reach, cameraZ - reach, reach * 2.0, reach * 2.0));
        }
        if (centreX == CENTRE_X && centreZ == CENTRE_Z) {
            crosshair(graphics, pixels / 2, pixels / 2);
        }
        scaleBar(graphics, blocksPerPixel, 12, pixels - 14, pixels);
        caption(graphics, pixels, String.format(Locale.ROOT, "%s | tick %,.0f (%.1f min) | %,.0f x %,.0f blocks | %s",
            weather, tick, (tick - TICK) / 1_200.0, half * 2.0, half * 2.0, types ? "types and formation areas" : "top view"));
        graphics.dispose();
        return image;
    }

    /** The slice through the thunderstorm nearest the camera, or through the camera if there is none. */
    static BufferedImage stormSlice(CloudField field, double tick, String weather, double half) {
        CloudFormation nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (CloudFormation formation : field.sky().formations()) {
            if (formation.type() != CloudType.CUMULONIMBUS || formation.lifeAt(tick) < 0.5f) {
                continue;
            }
            double dx = formation.centreXAt(tick) - CENTRE_X;
            double dz = formation.centreZAt(tick) - CENTRE_Z;
            double distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = formation;
            }
        }
        if (nearest == null) {
            return slice(field, tick, weather, CENTRE_X, CENTRE_Z, half, "through the camera");
        }
        return slice(field, tick, weather, nearest.centreXAt(tick), nearest.centreZAt(tick), half, "through the nearest thunderstorm");
    }

    /**
     * A side slice along x, {@code 2 * half} blocks wide around ({@code centreX}, {@code sliceZ}),
     * from y 40 to the thunderstorms' tops. Cloud is greyer where stormy and a little darker low down, with the sea level, vanilla's cloud height and the build
     * limit marked.
     */
    static BufferedImage slice(CloudField field, double tick, String weather, double centreX, double sliceZ, double half, String where) {
        int width = 1_000;
        double lowest = 40.0;
        double highest = BASE + 1_360.0;
        double blocksPerPixel = half * 2.0 / width;
        double blocksPerRow = 2.0;
        int height = (int) Math.ceil((highest - lowest) / blocksPerRow);
        double driftX = field.driftX(tick);
        double driftZ = field.driftZ(tick);
        CloudField.Area area = field.area(tick, centreX - half - driftX, sliceZ - driftZ, centreX + half - driftX, sliceZ - driftZ);
        CloudColumn column = new CloudColumn();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int px = 0; px < width; px++) {
            double worldX = centreX - half + (px + 0.5) * blocksPerPixel;
            area.column(worldX - driftX, sliceZ - driftZ, blocksPerPixel, column);
            for (int py = 0; py < height; py++) {
                double y = highest - (py + 0.5) * blocksPerRow;
                double skyShade = (y - lowest) / (highest - lowest);
                Color sky = mix(new Color(0x9C, 0xC4, 0xE6), new Color(0x3E, 0x6E, 0xB0), skyShade);
                if (y < 63.0) {
                    sky = new Color(0x2E, 0x55, 0x3A);
                }
                // Where spans overlap, the densest decides how solid and the stormiest how dark.
                float density = 0.0f;
                float storm = 0.0f;
                for (int span = 0; span < column.spanCount(); span++) {
                    if (y >= column.bottom(span) && y <= column.top(span)) {
                        density = Math.max(density, column.density(span));
                        storm = Math.max(storm, column.storminess(span));
                    }
                }
                if (density > 0.0f) {
                    double lit = 0.7 + 0.3 * Math.clamp((y - BASE + 80.0) / 600.0, 0.0, 1.0);
                    Color cloud = scale(mix(new Color(0xFA, 0xFA, 0xFA), new Color(0x60, 0x66, 0x70), storm), lit);
                    sky = mix(sky, cloud, Math.clamp(density * 1.4, 0.0, 1.0));
                }
                image.setRGB(px, py, sky.getRGB());
            }
        }
        Graphics2D graphics = graphics(image);
        heightLine(graphics, width, (highest - 63.0) / blocksPerRow, "y 63 sea level");
        heightLine(graphics, width, (highest - BASE) / blocksPerRow, "y 192 vanilla clouds");
        heightLine(graphics, width, (highest - 320.0) / blocksPerRow, "y 320 build limit");
        for (int y = 400; y < highest; y += 200) {
            heightLine(graphics, width, (highest - y) / blocksPerRow, "y " + y);
        }
        if (centreX == CENTRE_X && sliceZ == CENTRE_Z) {
            crosshair(graphics, width / 2, height - 20);
        }
        scaleBar(graphics, blocksPerPixel, 12, height - 14, width);
        double exaggeration = blocksPerPixel / blocksPerRow;
        caption(graphics, width, String.format(Locale.ROOT, "%s | tick %,.0f | side slice along x at z %,.0f (%s), %,.0f blocks wide | heights %s",
            weather, tick, sliceZ, where, half * 2.0,
            exaggeration > 1.05 ? String.format(Locale.ROOT, "drawn %.0f times taller", exaggeration) : "true to scale"));
        graphics.dispose();
        return image;
    }

    /** The highest solid span (thin cirrus over a cumulus lets it through), or the densest if none is solid. */
    private static int seenFromAbove(CloudColumn column) {
        int shown = -1;
        int densest = 0;
        for (int span = 0; span < column.spanCount(); span++) {
            if (column.density(span) >= 0.5f && (shown < 0 || column.top(span) > column.top(shown))) {
                shown = span;
            }
            if (column.density(span) > column.density(densest)) {
                densest = span;
            }
        }
        return shown >= 0 ? shown : densest;
    }

    private static void outlines(Graphics2D graphics, CloudField field, double tick, double centreX, double centreZ,
                                 double half, double blocksPerPixel) {
        graphics.setStroke(new BasicStroke(1.2f));
        for (CloudFormation formation : field.sky().formations()) {
            if (formation.everywhere() || !formation.aliveAt(tick)) {
                continue;
            }
            double scale = 0.5 + 0.5 * formation.lifeAt(tick);
            double stretchRoot = Math.sqrt(formation.stretch());
            double longAxis = formation.radius() * scale * stretchRoot / blocksPerPixel;
            double shortAxis = formation.radius() * scale / stretchRoot / blocksPerPixel;
            double x = (formation.centreXAt(tick) - centreX + half) / blocksPerPixel;
            double z = (formation.centreZAt(tick) - centreZ + half) / blocksPerPixel;
            AffineTransform saved = graphics.getTransform();
            graphics.translate(x, z);
            graphics.rotate(formation.angle());
            Color colour = typeColour(formation.type());
            graphics.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 200));
            graphics.draw(new Ellipse2D.Double(-longAxis, -shortAxis, longAxis * 2.0, shortAxis * 2.0));
            graphics.setTransform(saved);
        }
    }

    private static void legend(Graphics2D graphics, int pixels) {
        int y = 40;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (CloudType type : CloudType.values()) {
            graphics.setColor(typeColour(type));
            graphics.fillRect(pixels - 150, y - 11, 14, 14);
            label(graphics, type.id(), pixels - 130, y);
            y += 20;
        }
    }

    static Color typeColour(CloudType type) {
        return switch (type) {
            case CUMULUS -> new Color(0xF5, 0xF5, 0xF5);
            case CONGESTUS -> new Color(0xFF, 0xD5, 0x6B);
            case CUMULONIMBUS -> new Color(0xE0, 0x55, 0x55);
            case NIMBOSTRATUS -> new Color(0x8C, 0x93, 0xA6);
            case STRATUS -> new Color(0xB5, 0xA8, 0x9A);
            case STRATOCUMULUS -> new Color(0x7F, 0xC8, 0x8A);
            case ALTOCUMULUS -> new Color(0x6F, 0xB7, 0xF0);
            case CIRRUS -> new Color(0xC7, 0x8B, 0xF2);
        };
    }

    private static Color cloudColour(float top, float storm) {
        double high = Math.clamp((top - BASE) / 900.0, 0.0, 1.0);
        Color white = mix(new Color(0xFF, 0xFB, 0xF2), new Color(0xDC, 0xEA, 0xFF), high);
        return mix(white, new Color(0x6A, 0x70, 0x7A), storm * 0.8);
    }

    private static void heightLine(Graphics2D graphics, int width, double row, String text) {
        graphics.setColor(new Color(255, 255, 255, 60));
        graphics.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[] {4.0f, 6.0f}, 0.0f));
        graphics.drawLine(0, (int) row, width, (int) row);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        label(graphics, text, width - 8 - graphics.getFontMetrics().stringWidth(text), (int) row - 3);
    }

    private static void crosshair(Graphics2D graphics, int x, int y) {
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.setColor(new Color(255, 80, 80));
        graphics.drawLine(x - 7, y, x + 7, y);
        graphics.drawLine(x, y - 7, x, y + 7);
    }

    /** A bar of a round number of blocks near a fifth of the picture's width. */
    private static void scaleBar(Graphics2D graphics, double blocksPerPixel, int x, int y, int width) {
        double target = width * 0.2 * blocksPerPixel;
        double[] steps = {50, 100, 200, 250, 500, 1_000, 2_000, 2_500, 5_000, 10_000};
        double blocks = steps[0];
        for (double step : steps) {
            if (step <= target) {
                blocks = step;
            }
        }
        int length = (int) Math.round(blocks / blocksPerPixel);
        graphics.setStroke(new BasicStroke(3.0f));
        graphics.setColor(LABEL_SHADOW);
        graphics.drawLine(x + 1, y + 1, x + length + 1, y + 1);
        graphics.setColor(LABEL);
        graphics.drawLine(x, y, x + length, y);
        graphics.drawLine(x, y - 5, x, y + 3);
        graphics.drawLine(x + length, y - 5, x + length, y + 3);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        label(graphics, String.format(Locale.ROOT, "%,.0f blocks", blocks), x, y - 8);
    }

    private static void caption(Graphics2D graphics, int width, String text) {
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, width < 700 ? 11 : 14));
        label(graphics, text, 10, 20);
    }

    private static void label(Graphics2D graphics, String text, int x, int y) {
        graphics.setColor(LABEL_SHADOW);
        graphics.drawString(text, x + 1, y + 1);
        graphics.setColor(LABEL);
        graphics.drawString(text, x, y);
    }

    private static BufferedImage sheet(List<BufferedImage> frames, int columns) {
        int tile = frames.getFirst().getWidth();
        int rows = (frames.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(tile * columns + (columns - 1) * 4, tile * rows + (rows - 1) * 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int i = 0; i < frames.size(); i++) {
            graphics.drawImage(frames.get(i), (i % columns) * (tile + 4), (i / columns) * (tile + 4), null);
        }
        graphics.dispose();
        return sheet;
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return graphics;
    }

    private static Color mix(Color from, Color to, double amount) {
        double t = Math.clamp(amount, 0.0, 1.0);
        return new Color(
            (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }

    private static Color scale(Color colour, double amount) {
        return new Color(
            (int) Math.clamp(Math.round(colour.getRed() * amount), 0, 255),
            (int) Math.clamp(Math.round(colour.getGreen() * amount), 0, 255),
            (int) Math.clamp(Math.round(colour.getBlue() * amount), 0, 255));
    }

    private static void write(File folder, String name, BufferedImage image) throws IOException {
        ImageIO.write(image, "png", new File(folder, name + ".png"));
    }
}
