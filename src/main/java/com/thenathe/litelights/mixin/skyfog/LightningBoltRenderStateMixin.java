package com.thenathe.litelights.mixin.skyfog;

import com.thenathe.litelights.render.feature.lightning.BoltLife;
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the bolt's life from extraction to drawing, for the branching bolt's flicker. */
@Mixin(LightningBoltRenderState.class)
abstract class LightningBoltRenderStateMixin implements BoltLife {
    @Unique
    private int litelights$life;
    @Unique
    private float litelights$partialTick;

    @Override
    public int litelights$life() {
        return litelights$life;
    }

    @Override
    public float litelights$partialTick() {
        return litelights$partialTick;
    }

    @Override
    public void litelights$setLife(int life, float partialTick) {
        litelights$life = life;
        litelights$partialTick = partialTick;
    }
}
