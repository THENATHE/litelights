package com.thenathe.litelights.seedhelper;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The seed helper's two messages, on one channel each (docs/FAR-TERRAIN.md, "The seed helper"):
 * a client with LiteLights asks, the server's LiteLights answers. Plain data, shared by both
 * sides; nothing here names a client class, so it loads on a dedicated server.
 *
 * <ul>
 *   <li>{@link Request}: the client's protocol version and the dimension it asks for.</li>
 *   <li>{@link Reply}: the server's protocol version, whether it shares the seed with this player
 *   (and why not, if not), the seed when it does, and the server's world-generation fingerprint
 *   (what the client compares with its own to say which mods and packs differ).</li>
 * </ul>
 * A newer side's message is read by an older one as far as the fields go; a protocol older than
 * {@link #PROTOCOL} on either side gets a reply saying so and no seed.
 */
public final class SeedPayloads {
    public static final int PROTOCOL = 1;
    /** Far beyond any real fingerprint (a large modpack's is tens of kilobytes). */
    private static final int MAX_REPLY_BYTES = 4 << 20;
    private static boolean registered;
    private static final StreamCodec<ByteBuf, Map<String, String>> ENTRIES =
        ByteBufCodecs.<ByteBuf, String, String, Map<String, String>>map(size -> new TreeMap<>(), ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8);

    private SeedPayloads() {
    }

    public record Request(int protocol, String dimension) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("litelights", "seed_request"));
        public static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Request::protocol,
            ByteBufCodecs.STRING_UTF8, Request::dimension, Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * @param refusal empty when the seed is sent; otherwise "off", "not_allowed", "offline_mode",
     *                "too_often" or "protocol"
     */
    public record Reply(int protocol, String refusal, long seed, Map<String, String> fingerprint) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("litelights", "seed_reply"));
        public static final StreamCodec<ByteBuf, Reply> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Reply::protocol,
            ByteBufCodecs.STRING_UTF8, Reply::refusal, ByteBufCodecs.LONG, Reply::seed,
            ENTRIES, Reply::fingerprint, Reply::new);

        public boolean shared() {
            return refusal.isEmpty();
        }

        @Override
        public Type<Reply> type() {
            return TYPE;
        }

        /** Never the seed. */
        @Override
        public String toString() {
            return "Reply[protocol " + protocol + ", " + (shared() ? "seed shared" : "refused: " + refusal) + ", " + fingerprint.size() + " entries]";
        }
    }

    /** Registers both messages' types; both sides call it as they start, and it runs once. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Reply.TYPE, Reply.CODEC, MAX_REPLY_BYTES);
    }
}
