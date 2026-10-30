package com.youstream.sftp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyProtocolAcceptorTest {

    private static final byte[] SSH = "SSH-2.0-OpenSSH_9.9\r\n".getBytes(StandardCharsets.US_ASCII);

    private static ProxyProtocolAcceptor.Header parse(byte[] data) {
        return ProxyProtocolAcceptor.parse(data, 0, data.length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void readsVersionOneHeaders() {
        byte[] header = "PROXY TCP4 203.0.113.7 10.0.0.5 51234 2222\r\n".getBytes(StandardCharsets.US_ASCII);
        ProxyProtocolAcceptor.Header h = parse(concat(header, SSH));
        assertThat(h.length()).isEqualTo(header.length);
        assertThat(h.client()).isEqualTo(new InetSocketAddress("203.0.113.7", 51234));

        ProxyProtocolAcceptor.Header v6 = parse("PROXY TCP6 2001:db8::1 2001:db8::2 4000 22\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(v6.client().getAddress().getHostAddress()).isEqualTo("2001:db8:0:0:0:0:0:1");

        ProxyProtocolAcceptor.Header unknown = parse("PROXY UNKNOWN\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(unknown.client()).isNull();
        assertThat(unknown.length()).isEqualTo(15);
    }

    @Test
    void readsVersionTwoHeaders() {
        byte[] header = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
            0x21, 0x11, 0x00, 0x0C,               // v2 PROXY, TCP over IPv4, 12 address bytes
            (byte) 203, 0, 113, 7, 10, 0, 0, 5,   // source, destination
            (byte) 0xC8, 0x22, 0x08, (byte) 0xAE  // source port 51234, destination port 2222
        };
        ProxyProtocolAcceptor.Header h = parse(concat(header, SSH));
        assertThat(h.length()).isEqualTo(28);
        assertThat(h.client()).isEqualTo(new InetSocketAddress("203.0.113.7", 51234));

        byte[] local = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A, 0x20, 0x00, 0x00, 0x00};
        assertThat(parse(local).client()).isNull();
    }

    @Test
    void waitsForIncompleteHeaders() {
        assertThat(parse("PRO".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(parse("PROXY TCP4 203.0.113.7".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(parse(new byte[] {0x0D, 0x0A, 0x0D})).isNull();
    }

    @Test
    void rejectsConnectionsWithoutAHeader() {
        assertThatThrownBy(() -> parse(SSH)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse("PROXY TCP4 evil.example 10.0.0.5 1 2\r\n".getBytes(StandardCharsets.US_ASCII)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parse("PROXY TCP4 1.2.3.4 10.0.0.5 99999 2\r\n".getBytes(StandardCharsets.US_ASCII)))
            .isInstanceOf(IllegalStateException.class);
    }
}
