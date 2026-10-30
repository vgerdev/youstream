package com.youstream.sftp;

import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.session.AbstractServerSession;
import org.apache.sshd.server.session.ServerProxyAcceptor;
import org.apache.sshd.server.session.ServerSession;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads the PROXY protocol header (v1 text or v2 binary) that a load balancer sends before the SSH stream, and makes
 * the original client address the session's client address. Without it every login would appear to come from the
 * load balancer, which defeats the per-IP login throttle. Only enable it when the port is reachable solely through a
 * load balancer that always sends the header: a direct client could otherwise claim any address.
 */
class ProxyProtocolAcceptor implements ServerProxyAcceptor {

    private static final byte[] V1_PREFIX = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] V2_SIGNATURE = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
    /** Longest valid v1 header, including CRLF. */
    private static final int V1_MAX = 107;
    private static final int V2_HEADER = 16;

    /** A parsed header: its length in bytes and the client it names (null for LOCAL/UNKNOWN: keep the peer). */
    record Header(int length, InetSocketAddress client) {
    }

    /**
     * @return true once the header is consumed, false while more bytes are needed
     * @throws IllegalStateException when the stream does not start with a valid header
     */
    @Override
    public boolean acceptServerProxyMetadata(ServerSession session, Buffer buffer) {
        Header header = parse(buffer.array(), buffer.rpos(), buffer.available());
        if (header == null) {
            return false;
        }
        if (header.client() != null && session instanceof AbstractServerSession s) {
            s.setClientAddress(header.client());
        }
        buffer.rpos(buffer.rpos() + header.length());
        return true;
    }

    /** Parses a header at {@code data[start..start+available)}; null when more bytes are needed. */
    static Header parse(byte[] data, int start, int available) {
        if (startsWith(data, start, available, V2_SIGNATURE)) {
            return parseV2(data, start, available);
        }
        if (startsWith(data, start, available, V1_PREFIX)) {
            return parseV1(data, start, available);
        }
        if (available < V2_SIGNATURE.length && (isPrefix(data, start, available, V1_PREFIX)
            || isPrefix(data, start, available, V2_SIGNATURE))) {
            return null;
        }
        throw new IllegalStateException("connection did not start with a PROXY protocol header");
    }

    private static Header parseV1(byte[] data, int start, int available) {
        int end = -1;
        for (int i = start; i < start + Math.min(available, V1_MAX) - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                end = i;
                break;
            }
        }
        if (end < 0) {
            if (available >= V1_MAX) {
                throw new IllegalStateException("PROXY v1 header too long");
            }
            return null;
        }
        String[] parts = new String(data, start, end - start, StandardCharsets.US_ASCII).split(" ");
        int length = end + 2 - start;
        // PROXY TCP4|TCP6 <src> <dst> <srcport> <dstport>, or PROXY UNKNOWN (health checks): keep the peer address
        if (parts.length == 6 && (parts[1].equals("TCP4") || parts[1].equals("TCP6"))) {
            return new Header(length, new InetSocketAddress(parseAddress(parts[2]), parsePort(parts[4])));
        }
        if (parts.length >= 2 && parts[1].equals("UNKNOWN")) {
            return new Header(length, null);
        }
        throw new IllegalStateException("malformed PROXY v1 header");
    }

    private static Header parseV2(byte[] data, int start, int available) {
        if (available < V2_HEADER) {
            return null;
        }
        int versionCommand = data[start + 12] & 0xFF;
        int family = data[start + 13] & 0xFF;
        int length = ((data[start + 14] & 0xFF) << 8) | (data[start + 15] & 0xFF);
        if (versionCommand >> 4 != 2) {
            throw new IllegalStateException("unsupported PROXY protocol version");
        }
        if (available < V2_HEADER + length) {
            return null;
        }
        int a = start + V2_HEADER;
        boolean proxied = (versionCommand & 0x0F) == 1;
        InetSocketAddress client = null;
        // family/transport: 0x11 = TCP over IPv4 (12 address bytes), 0x21 = TCP over IPv6 (36); LOCAL keeps the peer
        if (proxied && family == 0x11 && length >= 12) {
            client = new InetSocketAddress(address(Arrays.copyOfRange(data, a, a + 4)), port(data, a + 8));
        } else if (proxied && family == 0x21 && length >= 36) {
            client = new InetSocketAddress(address(Arrays.copyOfRange(data, a, a + 16)), port(data, a + 32));
        }
        return new Header(V2_HEADER + length, client);
    }

    private static InetAddress parseAddress(String literal) {
        // only numeric literals are valid here; reject names so this never triggers a DNS lookup
        if (!literal.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalStateException("malformed address in PROXY header");
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("malformed address in PROXY header", e);
        }
    }

    private static int parsePort(String port) {
        try {
            int p = Integer.parseInt(port);
            if (p >= 0 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalStateException("malformed port in PROXY header");
    }

    private static InetAddress address(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int port(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static boolean startsWith(byte[] data, int start, int available, byte[] prefix) {
        return available >= prefix.length && isPrefix(data, start, prefix.length, prefix);
    }

    /** True when the first {@code available} bytes agree with {@code full}. */
    private static boolean isPrefix(byte[] data, int start, int available, byte[] full) {
        for (int i = 0; i < available && i < full.length; i++) {
            if (data[start + i] != full[i]) {
                return false;
            }
        }
        return true;
    }
}
