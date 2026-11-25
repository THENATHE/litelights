package com.thenathe.litelights.gui;

import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * The mods LiteLights works with, recommends or can't run beside, for the About page's status
 * overview. Each is found by its Fabric mod id (checked against the released jars); what LiteLights
 * does with it is the translation of {@code litelights.about.mod.<key>}, and a mod that isn't
 * installed links to its Modrinth page when it has one, or else to the page given in full.
 */
public final class ModStatus {
    public enum Group { RECOMMENDED, WORKS_WITH, NOT_COMPATIBLE }

    /**
     * {@code modrinthSlug} is null for the incompatible mods; a mod that isn't on Modrinth gives its
     * page's whole address there instead (Weather Glass, on CurseForge only).
     */
    public record Mod(String key, String name, String modId, Group group, String modrinthSlug) {
        public String whatKey() {
            return "litelights.about.mod." + key;
        }

        public String link() {
            if (modrinthSlug == null) {
                return null;
            }
            return modrinthSlug.startsWith("https://") ? modrinthSlug : "https://modrinth.com/mod/" + modrinthSlug;
        }

        /** The installed version, or empty when the mod isn't loaded. */
        public Optional<String> installedVersion() {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString());
        }
    }

    public static final List<Mod> MODS = List.of(
            new Mod("sodium", "Sodium", "sodium", Group.RECOMMENDED, "sodium"),
            new Mod("lambdynamiclights", "LambDynamicLights", "lambdynlights", Group.RECOMMENDED, "lambdynamiclights"),
            new Mod("lambdabettergrass", "LambdaBetterGrass", "lambdabettergrass", Group.RECOMMENDED, "lambdabettergrass"),
            new Mod("continuity", "Continuity", "continuity", Group.RECOMMENDED, "continuity"),
            new Mod("visuality", "Visuality", "visuality", Group.RECOMMENDED, "visuality"),
            new Mod("subtle_effects", "Subtle Effects", "subtle_effects", Group.RECOMMENDED, "subtle-effects"),
            new Mod("modmenu", "Mod Menu", "modmenu", Group.RECOMMENDED, "modmenu"),
            new Mod("particle_rain", "Particle Rain", "particlerain", Group.WORKS_WITH, "particle-rain"),
            new Mod("immersive_storms", "Immersive Storms", "immersive-storms", Group.WORKS_WITH, "immersive-storms"),
            new Mod("weather_glass", "Weather Glass", "weatherglass", Group.WORKS_WITH,
                    "https://www.curseforge.com/minecraft/mc-mods/weather-glass"),
            new Mod("wakes", "Wakes", "wakes", Group.WORKS_WITH, "wakes"),
            new Mod("big_water", "Big Water", "bigwater", Group.WORKS_WITH, "big-water"),
            new Mod("streams_reflowing", "Streams Reflowing", "streamsreflowing", Group.WORKS_WITH, "streams-reflowing"),
            new Mod("punchy", "Punchy!", "punchy", Group.WORKS_WITH, "punchy-fpa"),
            new Mod("sodium_extra", "Sodium Extra", "sodium-extra", Group.WORKS_WITH, "sodium-extra"),
            new Mod("reeses_sodium_options", "Reese's Sodium Options", "reeses-sodium-options", Group.WORKS_WITH, "reeses-sodium-options"),
            new Mod("lithium", "Lithium", "lithium", Group.WORKS_WITH, "lithium"),
            new Mod("krypton", "Krypton", "krypton", Group.WORKS_WITH, "krypton"),
            new Mod("scalablelux", "ScalableLux", "scalablelux", Group.WORKS_WITH, "scalablelux"),
            new Mod("better_block_entities", "Better Block Entities", "betterblockentities", Group.WORKS_WITH, "better-block-entities"),
            new Mod("ferritecore", "FerriteCore", "ferritecore", Group.WORKS_WITH, "ferrite-core"),
            new Mod("iris", "Iris", "iris", Group.NOT_COMPATIBLE, null),
            new Mod("dynamic_overlays", "Dynamic Overlays", "dynamic_overlays", Group.NOT_COMPATIBLE, null),
            // Support for both was dropped in 1.0.8; LiteLights steps aside while either is installed.
            new Mod("voxy", "Voxy", "voxy", Group.NOT_COMPATIBLE, null),
            new Mod("distanthorizons", "Distant Horizons", "distanthorizons", Group.NOT_COMPATIBLE, null));

    private ModStatus() {
    }

    public static List<Mod> inGroup(Group group) {
        return MODS.stream().filter(mod -> mod.group() == group).toList();
    }

    /** The card heading: the name in bold, then the status in its colour. */
    static Component heading(Mod mod) {
        Optional<String> version = mod.installedVersion();
        Component status;
        int colour;
        if (version.isEmpty()) {
            status = Component.translatable("litelights.about.status.not_installed");
            colour = Theme.TEXT_DIM;
        } else if (mod.group() == Group.NOT_COMPATIBLE) {
            status = Component.translatable("litelights.about.status.incompatible");
            colour = Theme.ERROR;
        } else {
            status = Component.translatable("litelights.about.status.installed", version.get());
            colour = Theme.GOOD;
        }
        return Component.literal(mod.name()).withStyle(style -> style.withBold(true).withColor(Credits.rgb(Theme.TEXT_STRONG)))
                .append(Component.literal("  "))
                .append(status.copy().withStyle(style -> style.withColor(Credits.rgb(colour))));
    }

    /** The name of the site a link goes to, for its button. */
    static String site(String link) {
        return link.startsWith("https://www.curseforge.com/") ? "CurseForge" : "Modrinth";
    }

    /** The Modrinth (or CurseForge) link for a mod that isn't installed, or null. */
    static String linkFor(Mod mod) {
        return mod.installedVersion().isEmpty() ? mod.link() : null;
    }
}
