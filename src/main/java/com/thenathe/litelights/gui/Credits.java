package com.thenathe.litelights.gui;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * Every project LiteLights credits, for the Credits page. Links go to Modrinth when the project is
 * there, otherwise CurseForge; Realistic Biomes is Marketplace content, so it links to
 * minecraft.net. The "used" text is the translation of {@code litelights.credits.<key>.used}.
 */
public final class Credits {
    public static final String PAGE = "credits";

    /** {@code site} names where the link goes, shown on its button. */
    public record Project(String key, String name, String author, String url, String site) {
        public String usedKey() {
            return "litelights.credits." + key + ".used";
        }
    }

    public static final List<Project> PROJECTS = List.of(
            new Project("immersivevfx", "ImmersiveVFX", "Shinyflvres",
                    "https://www.curseforge.com/minecraft/mc-mods/immersivevfx", "CurseForge"),
            new Project("realistic_biomes", "Realistic Biomes", "Oreville Studios",
                    "https://www.minecraft.net/en-us/article/lets-play-realistic-biomes", "minecraft.net"),
            new Project("big_water", "Big Water", "Tec", "https://modrinth.com/mod/big-water", "Modrinth"),
            new Project("particular", "Particular", "Chai", "https://modrinth.com/mod/particular", "Modrinth"),
            new Project("immersive_storms", "Immersive Storms", "TheDeathlyCow",
                    "https://modrinth.com/mod/immersive-storms", "Modrinth"),
            new Project("weather_glass", "Weather Glass", "duffopuffo",
                    "https://www.curseforge.com/minecraft/mc-mods/weather-glass", "CurseForge"),
            new Project("particle_rain", "Particle Rain", "pigcart", "https://modrinth.com/mod/particle-rain", "Modrinth"),
            new Project("wakes", "Wakes", "Goby56", "https://modrinth.com/mod/wakes", "Modrinth"),
            new Project("continuity", "Continuity", "Pepper_Bell", "https://modrinth.com/mod/continuity", "Modrinth"),
            new Project("lambdynamiclights", "LambDynamicLights", "LambdAurora",
                    "https://modrinth.com/mod/lambdynamiclights", "Modrinth"),
            new Project("lambdabettergrass", "LambdaBetterGrass", "LambdAurora",
                    "https://modrinth.com/mod/lambdabettergrass", "Modrinth"));

    private Credits() {
    }

    /** A project's name in bold, then "by" its author in the accent colour, as its card shows it. */
    static Component heading(Project project) {
        return Component.literal(project.name()).withStyle(style -> style.withBold(true).withColor(rgb(Theme.TEXT_STRONG)))
                .append(Component.literal("  "))
                .append(Component.translatable("litelights.credits.by", project.author())
                        .withStyle(style -> style.withColor(rgb(Theme.ACCENT))));
    }

    static TextColor rgb(int argb) {
        return TextColor.fromRgb(argb & 0xFFFFFF);
    }
}
