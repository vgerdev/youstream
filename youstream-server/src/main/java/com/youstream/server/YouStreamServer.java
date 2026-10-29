package com.youstream.server;

import com.youstream.api.YouStreamApp;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.undertow.UndertowServer;

/**
 * Entry point. Later phases also start the SFTP server and transcode workers here.
 */
public final class YouStreamServer {

    private YouStreamServer() {
    }

    public static void main(String[] args) {
        // WORKER mode: handlers do blocking JDBC/LDAP/file work
        Jooby.runApp(args, new UndertowServer(), ExecutionMode.WORKER, YouStreamApp::new);
    }
}
