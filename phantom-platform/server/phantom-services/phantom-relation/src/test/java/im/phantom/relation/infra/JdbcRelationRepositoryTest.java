package im.phantom.relation.infra;

import im.phantom.relation.domain.RelationRepository.FriendRequestRecord;
import im.phantom.relation.domain.RelationRepository.FriendGroupRecord;
import im.phantom.relation.support.RelationTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRelationRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcRelationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:relation-repo-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        RelationTestSchema.create(jdbc);
        RelationTestSchema.seedUser(jdbc, "app1", "u1", "Alice");
        RelationTestSchema.seedUser(jdbc, "app1", "u2", "Bob");
        repository = new JdbcRelationRepository(jdbc);
    }

    @Test
    void rejectsSelfRelationSelfRequestAndSelfBlacklist() {
        assertThatThrownBy(() -> repository.insertFriend("u1", "u1", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.insertBlacklist("u1", "u1", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);

        FriendRequestRecord request = request("r1", "u1", "u1", "PENDING", "u1:u1");
        assertThatThrownBy(() -> repository.insertFriendRequest(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniquePendingKey() {
        repository.insertFriendRequest(request("r1", "u1", "u2", "PENDING", "u1:u2"));

        assertThatThrownBy(() -> repository.insertFriendRequest(
                request("r2", "u1", "u2", "PENDING", "u1:u2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transitionClearsPendingKeyAndOnlySucceedsOnce() {
        repository.insertFriendRequest(request("r1", "u1", "u2", "PENDING", "u1:u2"));

        assertThat(repository.transitionRequest("r1", "PENDING", "ACCEPTED", 10L)).isTrue();
        assertThat(repository.transitionRequest("r1", "PENDING", "REJECTED", 11L)).isFalse();
        assertThat(repository.findFriendRequest("app1", "r1")).get()
                .extracting(FriendRequestRecord::status, FriendRequestRecord::pendingKey)
                .containsExactly("ACCEPTED", null);
    }

    @Test
    void friendAndGroupQueriesRemainScopedToOwner() {
        repository.insertFriend("u1", "u2", 2L);
        repository.insertFriendGroup("u1", new FriendGroupRecord("g1", "同事", 3L, 0));
        repository.replaceFriendGroups("u1", "u2", java.util.List.of("g1"));

        assertThat(repository.listFriends("u1")).singleElement().satisfies(friend -> {
            assertThat(friend.friendId()).isEqualTo("u2");
            assertThat(friend.groupIds()).containsExactly("g1");
        });
        assertThat(repository.listFriends("u2")).isEmpty();
        assertThat(repository.isGroupOwnedBy("g1", "u1")).isTrue();
        assertThat(repository.isGroupOwnedBy("g1", "u2")).isFalse();
    }

    private FriendRequestRecord request(String id, String from, String to, String status, String pendingKey) {
        return new FriendRequestRecord(id, "app1", from, to, "hi", status, 1L, null, null, pendingKey);
    }
}
