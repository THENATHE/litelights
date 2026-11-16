package com.thenathe.litelights.mixin.lightpost;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's render type factory is package-private; the enchantment outline's markers need their own render types. */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {
    @Invoker("create")
    static RenderType litelights$create(String name, RenderSetup state) {
        throw new AssertionError("Mixin didn't apply");
    }
}
