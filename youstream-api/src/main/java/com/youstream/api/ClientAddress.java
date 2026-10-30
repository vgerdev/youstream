package com.youstream.api;

import io.jooby.Route;

/**
 * Replaces the peer address with the client address from {@code X-Forwarded-For} when the server runs behind
 * {@code hops} trusted reverse proxies (a gateway, optionally a load balancer). Each proxy appends the address it
 * received the request from, so the client is the entry {@code hops} places from the end; anything before it was
 * sent by the client and cannot be trusted. Rate limits, the login throttle and anonymous view keys use the result.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static Route.Filter filter(int hops) {
        return next -> ctx -> {
            String client = resolve(ctx.header("X-Forwarded-For").valueOrNull(), hops);
            if (client != null) {
                ctx.setRemoteAddress(client);
            }
            return next.apply(ctx);
        };
    }

    /** The client address from a forwarded-for chain, or null to keep the peer address. */
    static String resolve(String forwardedFor, int hops) {
        if (hops <= 0 || forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        String[] chain = forwardedFor.split(",");
        String client = chain[Math.max(0, chain.length - hops)].trim();
        // an address literal only (IPv4, IPv6, optional brackets); anything else is ignored
        return client.matches("\\[?[0-9a-fA-F:.]+]?") ? client : null;
    }
}
