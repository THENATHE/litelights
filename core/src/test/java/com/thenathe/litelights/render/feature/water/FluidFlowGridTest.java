package com.thenathe.litelights.render.feature.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The surface flow's packing, which include/water/common.glsl (lite_fluid_flow_at) decodes. */
class FluidFlowGridTest {
    /** The shader's decoding: the texel's blue and alpha as unsigned bytes, made signed, in 64ths. */
    private static float[] decodeLikeTheShader(int texel) {
        int blue = (texel >>> 16) & 0xFF;
        int alpha = texel >>> 24;
        blue -= blue > 127 ? 256 : 0;
        alpha -= alpha > 127 ? 256 : 0;
        return new float[] {blue / 64.0f, alpha / 64.0f};
    }

    @Test
    void stillWaterAndUnknownColumnsAreZero() {
        assertEquals(0, FluidFlowGrid.pack(0.0, 0.0));
        FluidFlowGrid grid = new FluidFlowGrid();
        assertEquals(0, grid.texelBits(12, -40), "a column never set reads as still");
        float[] decoded = decodeLikeTheShader(grid.texelBits(12, -40));
        assertEquals(0.0f, decoded[0]);
        assertEquals(0.0f, decoded[1]);
    }

    @Test
    void vanillaUnitFlowsSurviveTheRoundTripInEveryDirection() {
        double diagonal = Math.sqrt(0.5);
        double[][] flows = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {diagonal, diagonal}, {-diagonal, diagonal}, {0.83, -0.79}, {-0.21, -0.31}};
        FluidFlowGrid grid = new FluidFlowGrid();
        for (double[] flow : flows) {
            grid.set(5, 9, FluidFlowGrid.pack(flow[0], flow[1]));
            float[] decoded = decodeLikeTheShader(grid.texelBits(5, 9));
            assertEquals(flow[0], decoded[0], 0.5 / 64.0 + 1e-6);
            assertEquals(flow[1], decoded[1], 0.5 / 64.0 + 1e-6);
            assertEquals(decoded[0], FluidFlowGrid.flowX(grid.get(5, 9)));
            assertEquals(decoded[1], FluidFlowGrid.flowZ(grid.get(5, 9)));
        }
    }

    @Test
    void strongCurrentsAndBadValuesAreClampedNotWrapped() {
        int packed = FluidFlowGrid.pack(5.0, -5.0);
        assertEquals(127 / 64.0f, FluidFlowGrid.flowX(packed));
        assertEquals(-127 / 64.0f, FluidFlowGrid.flowZ(packed));
        assertEquals(0, FluidFlowGrid.pack(Double.NaN, Double.POSITIVE_INFINITY));
    }

    @Test
    void leavesTheRainTopBytesAloneAndWrapsLikeTheColumnMap() {
        FluidFlowGrid grid = new FluidFlowGrid();
        grid.set(-1, 0, FluidFlowGrid.pack(1.0, -1.0));
        assertEquals(grid.get(-1, 0), grid.get(255, 0));
        int texel = grid.texelBits(-1, 0) | RainTopGrid.pack(80, -64);
        assertEquals(145, texel & 0xFFFF, "red and green still hold the rain top");
        float[] decoded = decodeLikeTheShader(texel);
        assertEquals(1.0f, decoded[0]);
        assertEquals(-1.0f, decoded[1]);
        grid.clearChunk(-1, 0);
        assertEquals(0, grid.get(-1, 0));
    }
}
