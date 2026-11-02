package com.youstream.server;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YouStreamServerTest {

    @Test
    void defaultChunkFitsUndertowsRequestLimit() {
        // the shipped defaults: a chunk over 2 MiB gets a bare 500 from Undertow before the app sees it
        var conf = ConfigFactory.parseFile(Path.of("../conf/application.conf").toFile())
            .withFallback(ConfigFactory.systemProperties())
            .resolve();
        assertThat(YouStreamServer.uploadChunkBytes(conf)).isEqualTo(1024 * 1024);
    }

    @Test
    void refusesChunksUndertowWouldReject() {
        var conf = ConfigFactory.parseString("upload.chunkBytes = 16m");
        assertThatThrownBy(() -> YouStreamServer.uploadChunkBytes(conf))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("upload.chunkBytes");
    }
}
