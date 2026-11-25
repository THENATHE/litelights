package com.thenathe.litelights.mixin.skyfog;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.thenathe.litelights.render.frame.ShownWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client level's weather layer of the environment attributes (the sky's, fog's and clouds'
 * greying, the sky light's darkening in rain) reads the shown weather, so with the Dynamic clouds
 * the land and sky darken with the clouds rather than five seconds after the server's rain. Only
 * the client's level: the server's attributes, which decide game rules, keep the real weather.
 */
@Mixin(EnvironmentAttributeSystem.class)
abstract class WeatherAccessMixin {
    @WrapOperation(method = "addDynamicLayers", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/attribute/WeatherAttributes$WeatherAccess;from(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/attribute/WeatherAttributes$WeatherAccess;"))
    private static WeatherAttributes.WeatherAccess litelights$shownWeather(Level level, Operation<WeatherAttributes.WeatherAccess> original) {
        WeatherAttributes.WeatherAccess real = original.call(level);
        if (!(level instanceof ClientLevel client)) {
            return real;
        }
        return new WeatherAttributes.WeatherAccess() {
            @Override
            public float rainLevel() {
                return ShownWeather.rain(client, 1.0f);
            }

            @Override
            public float thunderLevel() {
                return ShownWeather.thunder(client, 1.0f);
            }
        };
    }
}
