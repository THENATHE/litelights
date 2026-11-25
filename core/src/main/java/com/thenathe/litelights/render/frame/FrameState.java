package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.render.feature.vegetation.HangingSwing;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Everything the LiteFrame uniform block holds for one frame, filled in by the renderer before the
 * level draws. Field names follow the block in {@code assets/litelights/shaders/include/frame.glsl}.
 */
public final class FrameState {
    public final Matrix4f sunShadowMatrix = new Matrix4f();
    public final Matrix4f sunShadowMatrixPrevious = new Matrix4f();
    public final Matrix4f mobShadowMatrix = new Matrix4f();
    public final Matrix4f viewProjection = new Matrix4f();
    public final Matrix4f viewProjectionPrevious = new Matrix4f();
    public final Matrix4f inverseViewProjection = new Matrix4f();
    public final Vector4f sunDirection = new Vector4f(0, 1, 0, 1);
    public final Vector4f moonDirection = new Vector4f(0, -1, 0, -1);
    public final Vector4f shadowLightDirection = new Vector4f(0, 1, 0, 0);
    public final Vector4f sunColor = new Vector4f(1, 1, 1, 1);
    public final Vector4f ambientColor = new Vector4f(1, 1, 1, 0);
    public int cameraBlockX;
    public int cameraBlockY;
    public int cameraBlockZ;
    public int dimensionKind;
    public final Vector3f cameraFraction = new Vector3f();
    public final Vector4f time = new Vector4f();
    public final Vector4f weather = new Vector4f();
    /** Lightning (render.feature.lightning.CloudLightning fills it); all zero draws no flash. */
    public final Vector4f[] lightning = {new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f()};
    public final Vector4f shadowParams = new Vector4f();
    public final Vector4f shadowMapInfo = new Vector4f(1, 1, 1, 1);
    public final Vector4f shadowFade = new Vector4f(1, 0, 0, 0);
    public final Vector4f screen = new Vector4f(1, 1, 1, 1);
    public int featureFlags;
    public int pixelLockFlags;
    public int materialGridShift;
    /** Per-stream values; frame.glsl says what each component holds and which are unused. */
    public final Vector4f featureValuesA = new Vector4f();
    public final Vector4f featureValuesB = new Vector4f();
    public final Vector4f featureValuesC = new Vector4f();
    public final Vector4f featureValuesD = new Vector4f();
    // Sky and fog (render.feature.skyfog.SkyFogFeature fills these; see frame.glsl for each component).
    public final Vector4f hazeColor = new Vector4f();
    public final Vector4f hazeShape = new Vector4f();
    public final Vector4f fogRange = new Vector4f(Float.MAX_VALUE, Float.MAX_VALUE, 1, 0);
    public final Vector4f sunGlow = new Vector4f();
    public final Vector4f stormFog = new Vector4f();
    public final Vector4f skyZenith = new Vector4f();
    public final Vector4f skyInfo = new Vector4f();
    public final Vector4f endTint = new Vector4f(1, 1, 1, 0);
    // Weather on glass (render.feature.weather.GlassWeather); all zero draws nothing.
    public final Vector4f glassCold = new Vector4f();
    public final Vector4f glassInfo = new Vector4f();
    // Puddles (render.feature.weather.Puddles); x 0 draws none.
    public final Vector4f puddles = new Vector4f();
    // Snowy leaves and ground (render.feature.weather.SnowyLeaves); x 0 draws none.
    public final Vector4f snowCover = new Vector4f();
    // Swinging lanterns, chains and hanging signs (render.feature.vegetation.SwingingLanterns): x wind
    // strength, y amplitude scale, z columns listed; the cells and columns as HangingSwing packs them.
    public final Vector4f swing = new Vector4f();
    public final int[] swingCells = new int[HangingSwing.CELL_ROWS];
    public final int[] swingColumns = new int[HangingSwing.MAX_COLUMNS * 4];
    // The rocks ores are drawn on (render.material.OreRocks#uniforms): six vec4, two per rock.
    public final float[] oreRocks = new float[24];
}
