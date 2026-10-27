package im.phantom.relation.app;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.relation.CreateFriendRequestRequest;
import im.phantom.api.relation.DirectMessagePolicy;
import im.phantom.api.relation.FriendRequestAction;
import im.phantom.api.relation.QrCardDto;
import im.phantom.api.relation.UpdateFriendRequest;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.relation.infra.JdbcRelationRepository;
import im.phantom.relation.support.RelationTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationAppServiceTest {

    private JdbcTemplate jdbc;
    private JdbcRelationRepository repository;
    private CapturingEventWriter events;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:relation-service-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        RelationTestSchema.create(jdbc);
        RelationTestSchema.seedUser(jdbc, "app1", "u1", "Alice");
        RelationTestSchema.seedUser(jdbc, "app1", "u2", "Bob");
        RelationTestSchema.seedUser(jdbc, "app1", "u3", "Carol");
        RelationTestSchema.seedUser(jdbc, "app1", "u4", "Dave");
        RelationTestSchema.seedUser(jdbc, "app2", "u9", "Other App");
        repository = new JdbcRelationRepository(jdbc);
        events = new CapturingEventWriter();
        now = new AtomicLong(100L);
    }

    @Test
    void userCardIsExactPhantomIdWithinSameAppAndHiddenWhenBlockedByPeer() {
        RelationAppService service = service(true);

        var card = service.getUserCard("app1", "u1", "u2");
        assertThat(card.nickname()).isEqualTo("Bob");
        assertThat(card.friend()).isFalse();

        assertBiz(() -> service.getUserCard("app1", "u1", "missing"), ErrorCodes.USER_NOT_FOUND);
        assertBiz(() -> service.getUserCard("app1", "u1", "u9"), ErrorCodes.USER_NOT_FOUND);

        service.blockUser("app1", "u2", "u1");
        assertBiz(() -> service.getUserCard("app1", "u1", "u2"), ErrorCodes.USER_NOT_FOUND);
    }

    @Test
    void friendRequestValidatesMessageTargetAndDuplicatePendingApply() {
        RelationAppService service = service(true);

        assertBiz(() -> service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "x".repeat(31))), ErrorCodes.INVALID_PARAM);
        assertBiz(() -> service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u9", "hi")), ErrorCodes.USER_NOT_FOUND);

        var result = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "你好"));
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.autoAccepted()).isFalse();
        assertBiz(() -> service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "again")), ErrorCodes.DUPLICATE_APPLY);
    }

    @Test
    void alreadyFriendsCannotCreateAnotherRequest() {
        RelationAppService service = service(true);
        connect("u1", "u2");

        assertBiz(() -> service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "friend")), ErrorCodes.DUPLICATE_APPLY);
    }

    @Test
    void requestListTracksUnreadAndConcurrentAcceptIsHandledOnce() {
        RelationAppService service = service(true);
        var created = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "请通过"));

        var firstList = service.listFriendRequests("u2", true);
        assertThat(firstList.unreadCount()).isEqualTo(1);
        assertThat(firstList.items()).singleElement().satisfies(item -> {
            assertThat(item.direction()).isEqualTo("INCOMING");
            assertThat(item.peer().userId()).isEqualTo("u1");
            assertThat(item.unread()).isTrue();
        });
        assertThat(service.listFriendRequests("u2", true).unreadCount()).isZero();

        service.handleFriendRequest("app1", "u2", created.requestId(), FriendRequestAction.ACCEPT);
        assertThat(repository.isFriend("u1", "u2")).isTrue();
        assertThat(repository.isFriend("u2", "u1")).isTrue();
        assertBiz(() -> service.handleFriendRequest("app1", "u2", created.requestId(),
                FriendRequestAction.ACCEPT), ErrorCodes.ALREADY_HANDLED);
    }

    @Test
    void rejectAndIgnoreAreDistinctTerminalResults() {
        RelationAppService service = service(true);
        var rejected = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "reject"));
        service.handleFriendRequest("app1", "u2", rejected.requestId(), FriendRequestAction.REJECT);
        assertThat(repository.findFriendRequest("app1", rejected.requestId())).get()
                .extracting(r -> r.status()).isEqualTo("REJECTED");

        var ignored = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "ignore"));
        service.handleFriendRequest("app1", "u2", ignored.requestId(), FriendRequestAction.IGNORE);
        assertThat(repository.findFriendRequest("app1", ignored.requestId())).get()
                .extracting(r -> r.status()).isEqualTo("IGNORED");
        assertThat(repository.isFriend("u1", "u2")).isFalse();
    }

    @Test
    void remarkPinnedAndGroupsDriveFriendDisplayAndSurviveGroupDeletion() {
        RelationAppService service = service(true);
        connect("u1", "u2");
        var group = service.createFriendGroup("u1", "家人");

        var updated = service.updateFriend("u1", "u2",
                new UpdateFriendRequest("老王", true, List.of(group.groupId())));

        assertThat(updated.displayName()).isEqualTo("老王");
        assertThat(updated.pinned()).isTrue();
        assertThat(updated.groupIds()).containsExactly(group.groupId());
        assertThat(service.displayName("u1", "u2", "Bob")).isEqualTo("老王");
        assertThat(service.listFriends("u1")).singleElement().extracting("displayName").isEqualTo("老王");

        service.deleteFriendGroup("u1", group.groupId());
        assertThat(service.listFriends("u1")).singleElement().satisfies(friend -> {
            assertThat(friend.userId()).isEqualTo("u2");
            assertThat(friend.groupIds()).isEmpty();
        });
    }

    @Test
    void deletingFriendIsOneWayWhileBlacklistRemovesBothDirectionsAndBlocks() {
        RelationAppService service = service(true);
        connect("u1", "u2");

        service.deleteFriend("u1", "u2");
        assertThat(repository.isFriend("u1", "u2")).isFalse();
        assertThat(repository.isFriend("u2", "u1")).isTrue();

        connect("u1", "u2");
        service.blockUser("app1", "u1", "u2");
        assertThat(repository.isFriend("u1", "u2")).isFalse();
        assertThat(repository.isFriend("u2", "u1")).isFalse();
        assertThat(service.listBlacklist("u1")).extracting("userId").containsExactly("u2");
        assertBiz(() -> service.createFriendRequest("app1", "u2",
                new CreateFriendRequestRequest("u1", "hi")), ErrorCodes.REJECTED_BY_PEER);
        assertBiz(() -> service.authorizeDirectMessage("app1", "u2", "u1"), ErrorCodes.REJECTED_BY_PEER);
        assertBiz(() -> service.authorizeDirectMessage("app1", "u1", "u2"), ErrorCodes.IN_MY_BLACKLIST);

        service.unblockUser("u1", "u2");
        assertThat(service.listBlacklist("u1")).isEmpty();
        assertThat(repository.isFriend("u1", "u2")).isFalse();
        assertBiz(() -> service.authorizeDirectMessage("app1", "u1", "u2"), ErrorCodes.NOT_FRIEND);
    }

    @Test
    void directMessagePolicyUsesMergedRelationStateWithoutChangingErrorPriority() {
        RelationAppService service = service(true);
        connect("u1", "u2");
        assertThat(service.evaluateDirectMessage("app1", "u1", "u2")).isEqualTo(DirectMessagePolicy.ALLOWED);

        repository.deleteFriend("u2", "u1");
        assertThat(service.evaluateDirectMessage("app1", "u1", "u2"))
                .isEqualTo(DirectMessagePolicy.RESTRICTED);

        repository.deleteFriend("u1", "u2");
        assertThat(service.evaluateDirectMessage("app1", "u1", "u2"))
                .isEqualTo(DirectMessagePolicy.MESSAGE_REQUEST);

        repository.insertBlacklist("u2", "u1", 2L);
        assertThat(service.evaluateDirectMessage("app1", "u1", "u2"))
                .isEqualTo(DirectMessagePolicy.RESTRICTED);
        repository.insertBlacklist("u1", "u2", 3L);
        assertBiz(() -> service.evaluateDirectMessage("app1", "u1", "u2"),
                ErrorCodes.IN_MY_BLACKLIST);
    }

    @Test
    void autoAcceptAndFriendLimitAreEnforced() {
        RelationAppService service = service(true);
        jdbc.update("INSERT INTO user_relation_setting(user_id, auto_accept_requests, friend_limit, updated_at) " +
                "VALUES ('u2', TRUE, 10000, 1)");

        var auto = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "auto"));
        assertThat(auto.autoAccepted()).isTrue();
        assertThat(auto.status()).isEqualTo("ACCEPTED");
        assertThat(repository.isFriend("u1", "u2")).isTrue();

        repository.insertFriend("u3", "u4", 1L);
        jdbc.update("INSERT INTO user_relation_setting(user_id, auto_accept_requests, friend_limit, updated_at) " +
                "VALUES ('u3', FALSE, 1, 1)");
        assertBiz(() -> service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u3", "limit")), ErrorCodes.FRIEND_LIMIT_EXCEEDED);
    }

    @Test
    void qrRefreshInvalidatesOldTokenAndScanReturnsTargetCard() {
        RelationAppService service = service(true);

        QrCardDto first = service.getOrCreateQrCard("u1");
        assertThat(service.getOrCreateQrCard("u1").cardId()).isEqualTo(first.cardId());
        QrCardDto refreshed = service.refreshQrCard("u1");

        assertThat(refreshed.token()).isNotEqualTo(first.token());
        assertBiz(() -> service.scanQrCard("app1", "u2", first.token()), ErrorCodes.INVALID_PARAM);
        assertThat(service.scanQrCard("app1", "u2", refreshed.token()).user().userId()).isEqualTo("u1");

        jdbc.update("UPDATE qr_card SET expires_at=0 WHERE card_id=?", refreshed.cardId());
        assertBiz(() -> service.scanQrCard("app1", "u2", refreshed.token()), ErrorCodes.INVALID_PARAM);
    }

    @Test
    void relationChangesProduceExpectedSyncEventTypes() {
        RelationAppService service = service(true);
        var request = service.createFriendRequest("app1", "u1",
                new CreateFriendRequestRequest("u2", "event"));
        service.handleFriendRequest("app1", "u2", request.requestId(), FriendRequestAction.ACCEPT);
        service.updateFriend("u1", "u2", new UpdateFriendRequest("Alias", false, List.of()));
        service.deleteFriend("u1", "u2");
        service.blockUser("app1", "u2", "u1");
        service.unblockUser("u2", "u1");

        assertThat(events.events).extracting(event -> event.getRelation().getType()).contains(
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_RECEIVED,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_SENT,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_ACCEPTED,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_FRIEND_DELETED,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_ADDED,
                RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_REMOVED);
        assertThat(events.events).extracting(event -> event.getProfile().getType()).contains(
                im.phantom.proto.v1.ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_FRIEND_REMARK);
    }

    private void connect(String a, String b) {
        if (!repository.isFriend(a, b)) {
            repository.insertFriend(a, b, now.get());
        }
        if (!repository.isFriend(b, a)) {
            repository.insertFriend(b, a, now.get());
        }
    }

    private RelationAppService service(boolean relationHosted) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (relationHosted) {
            factory.registerSingleton("appConfigProvider", (AppConfigProvider) appId ->
                    new AppFeatureConfigDto(true, true, false, 0, true, 3));
        }
        return new RelationAppService(repository, events, now::get,
                factory.getBeanProvider(AppConfigProvider.class),
                new DefaultListableBeanFactory().getBeanProvider(
                        im.phantom.api.conversation.ConversationService.class));
    }

    private void assertBiz(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code()).isEqualTo(code);
    }

    private static final class CapturingEventWriter implements RelationEventWriter {
        private final List<SyncEvent> events = new ArrayList<>();

        @Override
        public void append(String userId, String eventId, SyncEvent event) {
            events.add(event);
        }
    }
}
