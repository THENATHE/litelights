package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.WorldAccess;
import com.thenathe.litelights.api.WorldIdentity;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Which world the player is in. In single player, data goes inside the world's save folder; in
 * multiplayer, under the game folder by server address and a hash of the seed hash the server
 * sends every client (biomeZoomSeed), so an address that starts a new world gets a new folder.
 */
final class CoreWorldAccess implements WorldAccess {
    @Override
    public @Nullable WorldIdentity current() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        String dimension = level.dimension().identifier().toString();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String key = fileSafe(save.getFileName() == null ? "world" : save.getFileName().toString());
            return new WorldIdentity(key, save.resolve("litelights"), true, dimension);
        }
        ServerData serverData = minecraft.getCurrentServer();
        String address = serverData == null ? "unknown-server" : serverData.ip;
        long seedHash = ((BiomeManagerAccessor) level.getBiomeManager()).litelights$biomeZoomSeed();
        String key = fileSafe(address) + "-" + seedTag(seedHash);
        Path folder = FabricLoader.getInstance().getGameDir().resolve("litelights").resolve("servers").resolve(key);
        return new WorldIdentity(key, folder, false, dimension);
    }

    /** Eight hex digits of a mix of the seed hash: enough to tell worlds apart, nothing to recover. */
    static String seedTag(long seedHash) {
        long mixed = seedHash * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 29;
        return HexFormat.of().toHexDigits((int) (mixed >>> 32));
    }

    /** Letters, digits, dots, dashes and underscores only, at most 64 characters. */
    static String fileSafe(String name) {
        StringBuilder safe = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            safe.append(plain ? c : '_');
            if (safe.length() == 64) {
                break;
            }
        }
        return safe.isEmpty() ? "world" : safe.toString();
    }
}
