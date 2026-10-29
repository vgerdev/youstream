package com.youstream.db.repo;

import com.youstream.common.PublicIds;
import com.youstream.common.model.Playlist;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.Visibility;
import com.youstream.db.Db;
import com.youstream.db.SearchQuery;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistAndSearchTest {

    private Db db;
    private VideoRepository videos;
    private PlaylistRepository playlists;
    private User alice;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        videos = new VideoRepository(db);
        playlists = new PlaylistRepository(db);
        alice = Fixtures.user(db, "alice");
    }

    private Video published(String title, String description, Visibility visibility) {
        Video v = Fixtures.video(db, alice, title);
        videos.updateMetadata(v.getId(), title, description, visibility);
        videos.markReady(v.getId(), 1000, 640, 360, List.of(), Instant.now());
        return videos.findById(v.getId()).orElseThrow();
    }

    @Test
    void searchQueriesAreSanitizedPrefixTerms() {
        assertThat(SearchQuery.of("Sail Carib")).contains("sail:* & carib:*");
        assertThat(SearchQuery.of("rock'n'roll & | ! (x)")).contains("rock:* & n:* & roll:* & x:*");
        assertThat(SearchQuery.of("Café Müller 2026")).contains("café:* & müller:* & 2026:*");
        assertThat(SearchQuery.of("  !!! ")).isEmpty();
        assertThat(SearchQuery.of(null)).isEmpty();
        assertThat(SearchQuery.of("a b c d e f g h i j").orElseThrow().split("&")).hasSize(8);
    }

    @Test
    void searchFindsPublicReadyVideosByPrefixAndStem() {
        Video sailing = published("Sailing the Caribbean", "Two weeks on a catamaran", Visibility.PUBLIC);
        published("Cooking pasta", "Carbonara, the Roman way", Visibility.PUBLIC);
        published("Secret sailing plans", "", Visibility.UNLISTED);
        Fixtures.video(db, alice, "Sailing draft"); // not transcoded

        assertThat(videos.search(SearchQuery.of("sail").orElseThrow(), 10, 0)).extracting(Video::getId).containsExactly(sailing.getId());
        assertThat(videos.search(SearchQuery.of("catamaran caribbean").orElseThrow(), 10, 0)).hasSize(1);
        assertThat(videos.search(SearchQuery.of("roman").orElseThrow(), 10, 0)).extracting(Video::getTitle).containsExactly("Cooking pasta");
        assertThat(videos.search(SearchQuery.of("submarine").orElseThrow(), 10, 0)).isEmpty();
    }

    @Test
    void channelSearchMatchesHandleAndDisplayName() {
        Fixtures.user(db, "bob");
        new UserRepository(db).updateProfile(alice.getId(), "alice.travels", "Alice Travels", "");

        assertThat(new UserRepository(db).searchChannels(SearchQuery.of("trav").orElseThrow(), 10, 0))
            .extracting(User::getHandle).containsExactly("alice.travels");
        assertThat(new UserRepository(db).searchChannels(SearchQuery.of("BOB").orElseThrow(), 10, 0)).hasSize(1);
    }

    @Test
    void playlistItemsKeepOrderAndCounts() {
        Playlist list = playlists.insert(Playlist.builder().publicId(PublicIds.next()).ownerId(alice.getId())
            .title("Best of 2026").visibility(Visibility.PUBLIC).build());
        Video a = published("a", "", Visibility.PUBLIC);
        Video b = published("b", "", Visibility.PUBLIC);
        Video c = published("c", "", Visibility.PUBLIC);

        assertThat(playlists.addItem(list.getId(), a.getId())).isTrue();
        assertThat(playlists.addItem(list.getId(), b.getId())).isTrue();
        assertThat(playlists.addItem(list.getId(), c.getId())).isTrue();
        assertThat(playlists.addItem(list.getId(), a.getId())).as("already there").isFalse();
        assertThat(playlists.findByPublicId(list.getPublicId()).orElseThrow().getItemCount()).isEqualTo(3);
        assertThat(playlists.items(list.getId())).extracting(Video::getTitle).containsExactly("a", "b", "c");

        playlists.reorder(list.getId(), List.of(c.getId(), a.getId(), 999L));
        assertThat(playlists.items(list.getId())).extracting(Video::getTitle).containsExactly("c", "a", "b");

        assertThat(playlists.removeItem(list.getId(), a.getId())).isTrue();
        assertThat(playlists.removeItem(list.getId(), a.getId())).isFalse();
        assertThat(playlists.playlistsContaining(alice.getId(), b.getId())).containsExactly(list.getId());
        assertThat(playlists.findByPublicId(list.getPublicId()).orElseThrow().getItemCount()).isEqualTo(2);

        assertThat(playlists.search(SearchQuery.of("best").orElseThrow(), 10, 0)).hasSize(1);
        playlists.update(list.getId(), "Best of 2026", "", Visibility.PRIVATE);
        assertThat(playlists.search(SearchQuery.of("best").orElseThrow(), 10, 0)).isEmpty();
        assertThat(playlists.listPublicByOwner(alice.getId())).isEmpty();
        assertThat(playlists.listByOwner(alice.getId())).hasSize(1);

        videos.delete(b.getId());
        assertThat(playlists.items(list.getId())).extracting(Video::getTitle).containsExactly("c");
        assertThat(playlists.delete(list.getId())).isTrue();
    }
}
