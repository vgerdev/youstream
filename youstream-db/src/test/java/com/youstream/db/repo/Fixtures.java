package com.youstream.db.repo;

import com.youstream.common.PublicIds;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoSource;
import com.youstream.db.Db;

/** Small builders for test rows. */
final class Fixtures {

    private Fixtures() {
    }

    static User user(Db db, String name) {
        return new UserRepository(db).insert(User.builder()
            .username(name)
            .displayName(name.toUpperCase())
            .email(name + "@example.com")
            .handle(name)
            .build());
    }

    static Video video(Db db, User owner, String title) {
        return new VideoRepository(db).insert(Video.builder()
            .publicId(PublicIds.next())
            .ownerId(owner.getId())
            .title(title)
            .source(VideoSource.WEB)
            .originalFilename(title + ".mp4")
            .sizeBytes(1234)
            .build());
    }
}
