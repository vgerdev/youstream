package com.youstream.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Connection settings for the PostgreSQL pool. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DbConfig {
    private String url;
    private String user;
    private String password;
    @Builder.Default
    private int poolSize = 10;
    /** Schema for tables and the Flyway history; also set as the connection search_path. */
    @Builder.Default
    private String schema = "public";
}
