package com.thenathe.litelights.mixin.skyfog;

import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a bolt is in its life: 0 or more while it is lit, below 0 between its strikes. */
@Mixin(LightningBolt.class)
public interface LightningBoltAccessor {
    @Accessor("life")
    int litelights$life();
}
