package com.youstream.sftp;

import com.youstream.common.storage.StorageService;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives every user a private, jailed SFTP root at {@code sftp/{userId}} in storage. The rooted file system
 * SSHD builds on top of it rejects any path outside that directory, including {@code ..} and symlink escapes.
 */
class UserFolderFileSystemFactory extends VirtualFileSystemFactory {

    private final StorageService storage;

    UserFolderFileSystemFactory(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public Path getUserHomeDir(SessionContext session) throws IOException {
        Path dir = userRoot(storage, SftpAuthenticators.userId((ServerSession) session));
        Files.createDirectories(dir);
        return dir;
    }

    static Path userRoot(StorageService storage, long userId) {
        return storage.localPath("sftp/" + userId);
    }
}
