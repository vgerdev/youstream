package com.youstream.api;

import io.jooby.StatusCode;
import io.jooby.test.MockRouter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YouStreamAppTest {

    @Test
    void healthReportsUpWhenDatabaseIsHealthy() {
        MockRouter router = new MockRouter(new YouStreamApp(AppServices.builder().databaseHealthy(() -> true).build()));

        router.get("/api/health", rsp -> {
            assertThat(rsp.getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(rsp.value()).isEqualTo(Map.of("status", "UP", "database", "UP"));
        });
    }

    @Test
    void healthReportsDownWhenDatabaseIsUnavailable() {
        MockRouter router = new MockRouter(new YouStreamApp(AppServices.builder().databaseHealthy(() -> false).build()));

        router.get("/api/health", rsp -> {
            assertThat(rsp.getStatusCode()).isEqualTo(StatusCode.SERVICE_UNAVAILABLE);
            assertThat(rsp.value()).isEqualTo(Map.of("status", "DOWN", "database", "DOWN"));
        });
    }
}
