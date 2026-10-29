package com.youstream.api;

import io.jooby.StatusCode;
import io.jooby.test.MockRouter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YouStreamAppTest {

    private final MockRouter router = new MockRouter(new YouStreamApp());

    @Test
    void healthReportsUp() {
        router.get("/api/health", rsp -> {
            assertThat(rsp.getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(rsp.value()).isEqualTo(Map.of("status", "UP"));
        });
    }
}
