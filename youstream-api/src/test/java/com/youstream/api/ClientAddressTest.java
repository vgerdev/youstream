package com.youstream.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @Test
    void takesTheEntryAddedByTheOutermostTrustedProxy() {
        assertThat(ClientAddress.resolve("203.0.113.7", 1)).isEqualTo("203.0.113.7");
        // the client sent a fake first entry; the gateway appended the real address
        assertThat(ClientAddress.resolve("1.2.3.4, 203.0.113.7", 1)).isEqualTo("203.0.113.7");
        // load balancer + gateway
        assertThat(ClientAddress.resolve("1.2.3.4, 203.0.113.7, 10.0.0.5", 2)).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.resolve("2001:db8::1", 1)).isEqualTo("2001:db8::1");
    }

    @Test
    void keepsThePeerAddressWhenNothingTrustworthyIsThere() {
        assertThat(ClientAddress.resolve("203.0.113.7", 0)).isNull();
        assertThat(ClientAddress.resolve(null, 1)).isNull();
        assertThat(ClientAddress.resolve("", 1)).isNull();
        assertThat(ClientAddress.resolve("evil<script>", 1)).isNull();
        // fewer entries than hops: the first one is the best available
        assertThat(ClientAddress.resolve("203.0.113.7", 2)).isEqualTo("203.0.113.7");
    }
}
