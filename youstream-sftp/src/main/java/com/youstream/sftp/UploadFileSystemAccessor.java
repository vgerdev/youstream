package com.youstream.sftp;

import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * File access policy for the upload folders: links cannot be created (no way to point outside the jail), and
 * listings show a neutral owner instead of the server's operating-system account.
 */
class UploadFileSystemAccessor implements SftpFileSystemAccessor {

    private record Principal(String name) implements GroupPrincipal {
        @Override
        public String getName() {
            return name;
        }

        /** SSHD renders owner/group in listings with toString(). */
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Principal OWNER = new Principal("youstream");

    @Override
    public NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy subsystem, Path file, int flags,
                                                                      NavigableMap<String, Object> attrs,
                                                                      LinkOption... options) {
        NavigableMap<String, Object> reported = new TreeMap<>(attrs.comparator());
        reported.putAll(attrs);
        // set even when absent: on Windows the group otherwise shows as the ACL placeholder "GROUP@"
        reported.put("owner", OWNER);
        reported.put("group", OWNER);
        return reported;
    }

    @Override
    public void createLink(SftpSubsystemProxy subsystem, Path link, Path existing, boolean symLink) throws IOException {
        throw new AccessDeniedException(link.toString(), null, "links are not allowed");
    }
}
