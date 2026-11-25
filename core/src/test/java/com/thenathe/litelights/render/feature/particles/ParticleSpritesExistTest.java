package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.feature.water.splash.WaterParticleSprites;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every particle sprite LiteLights asks for is in the shared particle texture folder. Three tools
 * write that folder (render_particle_sprites.py, draw_water_particles.py and draw_ember_veins.py); a missing file shows
 * in game as a magenta and black square, which is how a lost set of water sprites was found.
 */
class ParticleSpritesExistTest {
    private static final Path FOLDER = Path.of("src/main/resources/assets/litelights/textures/particle");

    @Test
    void everyParticleKindsSpritesExist() throws IllegalAccessException {
        List<String> missing = new ArrayList<>();
        for (Field field : ParticleKind.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ParticleKind.class) {
                ParticleKind kind = (ParticleKind) field.get(null);
                for (String sprite : kind.sprites) {
                    addIfMissing(missing, sprite);
                }
                for (String sprite : kind.hdSprites) {
                    addIfMissing(missing, sprite);
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing particle sprites: " + missing);
    }

    @Test
    void everyFireDecalSpriteExists() {
        List<String> missing = new ArrayList<>();
        for (ParticleSprites.Decal decal : ParticleSprites.Decal.values()) {
            for (String sprite : decal.spriteNames()) {
                addIfMissing(missing, sprite);
            }
        }
        assertTrue(missing.isEmpty(), "missing fire decal sprites: " + missing);
    }

    @Test
    void everyWaterSplashSpriteExists() throws ReflectiveOperationException {
        List<String> missing = new ArrayList<>();
        for (WaterParticleSprites.Set set : WaterParticleSprites.Set.values()) {
            Field name = WaterParticleSprites.Set.class.getDeclaredField("name");
            Field frames = WaterParticleSprites.Set.class.getDeclaredField("frames");
            name.setAccessible(true);
            frames.setAccessible(true);
            for (int frame = 0; frame < frames.getInt(set); frame++) {
                addIfMissing(missing, name.get(set) + "_" + frame);
            }
        }
        assertTrue(missing.isEmpty(), "missing water sprites: " + missing);
    }

    private static void addIfMissing(List<String> missing, String sprite) {
        if (!Files.isRegularFile(FOLDER.resolve(sprite + ".png"))) {
            missing.add(sprite);
        }
    }
}
