package com.thenathe.litelights.overlays.pack;

import com.thenathe.litelights.overlays.OverlaysModule;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * LambdaBetterGrass, an optional mod that redraws grass-like blocks' sides. When it's installed,
 * the overlays follow what it draws on each side face (see the variety processor and the
 * generator). Its {@code bettergrass} resources say which blocks it redraws; its mode and its
 * "better layers" switch are read from its config object while chunks are meshed, because it
 * changes them without a resource reload (it only meshes the land again). Nothing else of its
 * code is called.
 */
public final class BetterGrass {
    public static final String MOD_ID = "lambdabettergrass";

    /** Its modes, in its own order. */
    public enum Mode {
        OFF, FASTEST, FAST, FANCY
    }

    /**
     * What LambdaBetterGrass is set to right now.
     *
     * @param betterLayers its "better layers" switch, which draws grass under a plant among snow as snowy
     */
    public record Settings(Mode mode, boolean betterLayers) {
        public static final Settings ABSENT = new Settings(Mode.OFF, false);
        /** Its own defaults, used if its config can't be read. */
        static final Settings DEFAULTS = new Settings(Mode.FANCY, true);
    }

    private static volatile ConfigReader reader;

    private BetterGrass() {
    }

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Its current mode and better layers switch; {@link Settings#ABSENT} when it isn't installed. */
    public static Settings settings() {
        ConfigReader current = reader;
        if (current == null) {
            current = ConfigReader.create();
            reader = current;
        }
        return current.read();
    }

    /** Reads LambdaBetterGrass's config object through method handles looked up once. */
    private static final class ConfigReader {
        private static final ConfigReader NOT_INSTALLED = new ConfigReader(null, null, null, null, Settings.ABSENT);
        private static final ConfigReader UNREADABLE = new ConfigReader(null, null, null, null, Settings.DEFAULTS);

        private final MethodHandle instance;
        private final MethodHandle config;
        private final MethodHandle mode;
        private final MethodHandle betterLayers;
        private final Settings fixed;
        private boolean warned;

        private ConfigReader(MethodHandle instance, MethodHandle config, MethodHandle mode, MethodHandle betterLayers,
                             Settings fixed) {
            this.instance = instance;
            this.config = config;
            this.mode = mode;
            this.betterLayers = betterLayers;
            this.fixed = fixed;
        }

        static ConfigReader create() {
            if (!installed()) {
                return NOT_INSTALLED;
            }
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> mod = Class.forName("dev.lambdaurora.lambdabettergrass.LambdaBetterGrass");
                Class<?> configClass = Class.forName("dev.lambdaurora.lambdabettergrass.LBGConfig");
                Class<?> modeClass = Class.forName("dev.lambdaurora.lambdabettergrass.LBGMode");
                return new ConfigReader(
                    lookup.findStatic(mod, "get", MethodType.methodType(mod)),
                    lookup.findGetter(mod, "config", configClass),
                    lookup.findVirtual(configClass, "getMode", MethodType.methodType(modeClass)),
                    lookup.findVirtual(mod, "hasBetterLayer", MethodType.methodType(boolean.class)),
                    null);
            } catch (ReflectiveOperationException | LinkageError failure) {
                OverlaysModule.LOG.warn("Can't read LambdaBetterGrass's mode; overlays assume its defaults (fancy, "
                    + "better layers on): {}", failure.toString());
                return UNREADABLE;
            }
        }

        Settings read() {
            if (fixed != null) {
                return fixed;
            }
            try {
                Object mod = instance.invoke();
                if (mod == null) {
                    return Settings.DEFAULTS;
                }
                String name = ((Enum<?>) mode.invoke(config.invoke(mod))).name();
                return new Settings(Mode.valueOf(name), (boolean) betterLayers.invoke(mod));
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                if (!warned) {
                    warned = true;
                    OverlaysModule.LOG.warn("Can't read LambdaBetterGrass's mode; overlays assume its defaults: {}",
                        failure.toString());
                }
                return Settings.DEFAULTS;
            }
        }
    }
}
