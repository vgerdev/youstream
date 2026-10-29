package com.youstream.api.social;

import com.youstream.api.AppServices;
import com.youstream.api.auth.CurrentUser;
import com.youstream.api.dto.Page;
import com.youstream.api.dto.SocialDtos;
import com.youstream.api.video.VideoViews;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.Comment;
import com.youstream.common.model.Video;
import com.youstream.db.repo.CommentRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Comments on videos: newest-first threads with one level of replies. */
public final class CommentRoutes {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_BODY = 10_000;

    private final VideoRepository videos;
    private final CommentRepository comments;

    public CommentRoutes(AppServices services) {
        this.videos = new VideoRepository(services.getDb());
        this.comments = new CommentRepository(services.getDb());
    }

    public void install(Jooby app) {
        app.get("/api/videos/{publicId}/comments", this::list);
        app.post("/api/videos/{publicId}/comments", this::create);
        app.patch("/api/comments/{id}", this::edit);
        app.delete("/api/comments/{id}", this::delete);
    }

    /** Top-level comments ({@code ?cursor=<lastId>}), or replies with {@code ?parent=<id>&cursor=<lastId>}. */
    private Page<SocialDtos.Comment> list(Context ctx) {
        AccessClaims caller = ctx.getUser();
        Video video = VideoViews.requireViewable(videos.findByPublicId(ctx.path("publicId").value()), caller);
        Long cursor = longParam(ctx, "cursor");
        Long parent = longParam(ctx, "parent");
        List<Comment> page;
        if (parent != null) {
            comments.findById(parent).filter(p -> p.getVideoId().equals(video.getId()))
                .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "comment not found"));
            page = comments.listReplies(parent, cursor, PAGE_SIZE);
        } else {
            page = comments.listTopLevel(video.getId(), cursor, PAGE_SIZE);
        }
        String next = page.size() == PAGE_SIZE ? page.getLast().getId().toString() : null;
        return new Page<>(page.stream().map(c -> dto(c, video, caller)).toList(), next);
    }

    private SocialDtos.Comment create(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Video video = VideoViews.requireViewable(videos.findByPublicId(ctx.path("publicId").value()), caller);
        SocialDtos.NewComment req = ctx.body(SocialDtos.NewComment.class);
        Long parentId = null;
        if (req.getParentId() != null) {
            Comment parent = comments.findById(req.getParentId())
                .filter(p -> p.getVideoId().equals(video.getId()) && !p.isDeleted())
                .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "comment not found"));
            // one level of replies, as on YouTube
            parentId = parent.getParentId() != null ? parent.getParentId() : parent.getId();
        }
        Comment created = comments.insert(video.getId(), caller.getUserId(), parentId, validBody(req.getBody()));
        ctx.setResponseCode(StatusCode.CREATED);
        return dto(created, video, caller);
    }

    private SocialDtos.Comment edit(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Comment comment = existing(ctx);
        Video video = VideoViews.requireViewable(videos.findById(comment.getVideoId()), caller);
        if (comment.getUserId() != caller.getUserId()) {
            throw new StatusCodeException(StatusCode.FORBIDDEN, "only the author can edit a comment");
        }
        comments.updateBody(comment.getId(), validBody(ctx.body(SocialDtos.NewComment.class).getBody()), Instant.now());
        return dto(comments.findById(comment.getId()).orElseThrow(), video, caller);
    }

    private Context delete(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        Comment comment = existing(ctx);
        Video video = VideoViews.requireViewable(videos.findById(comment.getVideoId()), caller);
        if (!canDelete(comment, video, caller)) {
            throw new StatusCodeException(StatusCode.FORBIDDEN, "not allowed to delete this comment");
        }
        comments.delete(comment.getId(), Instant.now());
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private Comment existing(Context ctx) {
        Long id = longParam(ctx.path("id").valueOrNull());
        return Optional.ofNullable(id).flatMap(comments::findById).filter(c -> !c.isDeleted())
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "comment not found"));
    }

    /** Authors, the video owner and admins may delete. */
    private static boolean canDelete(Comment c, Video video, AccessClaims caller) {
        return caller != null && (caller.getUserId() == c.getUserId() || VideoViews.canEdit(video, caller));
    }

    private static SocialDtos.Comment dto(Comment c, Video video, AccessClaims caller) {
        boolean deleted = c.isDeleted();
        return SocialDtos.Comment.builder()
            .id(c.getId())
            .parentId(c.getParentId())
            .body(deleted ? null : c.getBody())
            .deleted(deleted)
            .author(deleted ? null : new SocialDtos.Author(c.getAuthorHandle(), c.getAuthorDisplayName()))
            .createdAt(c.getCreatedAt())
            .editedAt(c.getEditedAt())
            .replyCount(c.getReplyCount())
            .canEdit(!deleted && caller != null && caller.getUserId() == c.getUserId())
            .canDelete(!deleted && canDelete(c, video, caller))
            .build();
    }

    private static String validBody(String body) {
        String b = body == null ? "" : body.strip();
        if (b.isEmpty() || b.length() > MAX_BODY) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "comment must be 1 to " + MAX_BODY + " characters");
        }
        return b;
    }

    private static Long longParam(Context ctx, String name) {
        return longParam(ctx.query(name).valueOrNull());
    }

    private static Long longParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "invalid id");
        }
    }
}
