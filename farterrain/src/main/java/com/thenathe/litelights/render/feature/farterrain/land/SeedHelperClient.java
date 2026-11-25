package com.thenathe.litelights.render.feature.farterrain.land;

import com.thenathe.litelights.seedhelper.SeedPayloads;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of LiteLights' seed helper on a server ({@code SeedHelperServer}): asks once
 * a connection, and only when seed generation wants a seed and the server offers the channel (a
 * server without LiteLights never hears from it). The reply is kept in memory for the connection
 * only: the seed is never written to disk from here or to the log.
 */
final class SeedHelperClient {
    /** How long to wait for the helper before going on with a seed the player gave. */
    static final long WAIT_NANOS = 5_000_000_000L;

    /**
     * What the server's helper sent: the seed (null when it refused), the server's world
     * generation fingerprint entries (null when none came), and why it refused ("" when it didn't).
     */
    record Reply(@Nullable Long seed, @Nullable Map<String, String> fingerprint, String refusal) {
        @Override
        public String toString() {
            return "Reply[" + (seed == null ? "no seed" : "a seed") + ", " + (fingerprint == null ? "no fingerprint" : fingerprint.size() + " entries")
                + ", " + refusal + "]";
        }
    }

    private static @Nullable Object askedConnection;

    private SeedHelperClient() {
    }

    /** Listens for the helper's reply, as the module starts. */
    static void register(Consumer<Reply> replied) {
        SeedPayloads.register();
        ClientPlayNetworking.registerGlobalReceiver(SeedPayloads.Reply.TYPE, (reply, context) -> {
            Map<String, String> fingerprint = reply.fingerprint().isEmpty() ? null : Map.copyOf(reply.fingerprint());
            replied.accept(new Reply(reply.shared() ? reply.seed() : null, fingerprint, reply.refusal()));
        });
    }

    /** Whether this server's LiteLights can be asked: it registered the channel. */
    static boolean available() {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(SeedPayloads.Request.TYPE);
    }

    /** Asks once a connection; returns whether a request went out now. */
    static boolean ask(String dimension) {
        Object connection = Minecraft.getInstance().getConnection();
        if (connection == null || connection == askedConnection || !available()) {
            return false;
        }
        askedConnection = connection;
        ClientPlayNetworking.send(new SeedPayloads.Request(SeedPayloads.PROTOCOL, dimension));
        return true;
    }

    /** Whether this connection was already asked. */
    static boolean asked() {
        Object connection = Minecraft.getInstance().getConnection();
        return connection != null && connection == askedConnection;
    }
}
