package com.youstream.api;

import lombok.Builder;
import lombok.Value;

import java.util.function.BooleanSupplier;

/** Services the HTTP layer depends on; built by youstream-server. */
@Value
@Builder
public class AppServices {
    @Builder.Default
    BooleanSupplier databaseHealthy = () -> false;
}
