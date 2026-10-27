package im.phantom.conversation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.conversation.ConversationPageDto;
import im.phantom.api.conversation.ConversationSnapshotDto;
import im.phantom.api.conversation.UpdateConversationRequest;
import im.phantom.common.ConvIds;
import im.phantom.conversation.domain.ConversationRepository;
import im.phantom.conversation.infra.JdbcConversationRepository;
import im.phantom.conversation.support.ConversationTestSchema;
import im.phantom.proto.v1.ConvStateEvent;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.DraftEvent;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.TextContent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAppServiceTest {

    private JdbcTemplate jdbc;
    private ConversationRepository repository;
    private CapturingWriter events;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-service-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        ConversationTestSchema.create(jdbc);
        ConversationTestSchema.seedUser(jdbc, "u1", "Alice");
        ConversationTestSchema.seedUser(jdbc, "u2", "Bob");
        ConversationTestSchema.seedUser(jdbc, "u3", "Carol");
        ConversationTestSchema.seedUser(jdbc, "u4", "Dave");
        repository = new JdbcConversationRepository(jdbc);
        events = new CapturingWriter();
        now = new AtomicLong(100L);
    }

    @Test
    void firstMessageCreatesStableSingleConversationAndReceiverUnread() {
        ConversationAppService service = service();
        String convId = ConvIds.single("u1", "u2");

        deliver(service, textMsg("m1", convId, "u1", 1, now.get(), "hello"));

        ConversationPageDto alice = service.listConversations("u1", null, 20);
        ConversationPageDto bob = service.listConversations("u2", null, 20);
        assertThat(alice.items()).singleElement().satisfies(item -> {
            assertThat(item.convId()).isEqualTo(convId);
            assertThat(item.targetId()).isEqualTo("u2");
            assertThat(item.title()).isEqualTo("Bob");
            assertThat(item.unreadCount()).isZero();
            assertThat(item.lastMessageDigest()).isEqualTo("hello");
            assertThat(item.maxConvSeq()).isEqualTo(1);
        });
        assertThat(bob.items()).singleElement().satisfies(item -> {
            assertThat(item.targetId()).isEqualTo("u1");
            assertThat(item.unreadCount()).isEqualTo(1);
        });
    }

    @Test
    void pinnedConversationKeepsGroupOrderWhenOtherConversationReceivesMessage() {
        ConversationAppService service = service();
        String withBob = ConvIds.single("u1", "u2");
        String withCarol = ConvIds.single("u1", "u3");
        deliver(service, textMsg("m1", withBob, "u2", 1, 100, "bob"));
        deliver(service, textMsg("m2", withCarol, "u3", 1, 200, "carol"));
        now.set(300);
        service.updateConversation("u1", withBob, new UpdateConversationRequest(true, null));
        now.set(350);
        service.updateConversation("u1", withCarol, new UpdateConversationRequest(true, null));
        deliver(service, textMsg("m3", withBob, "u2", 2, 400, "newest"));

        ConversationPageDto page = service.listConversations("u1", null, 20);

        assertThat(page.items()).extracting(ConversationSnapshotDto::convId)
                .containsExactly(withCarol, withBob);
    }

    @Test
    void paginationReturnsCursorBaselineAndCompleteSnapshotFields() {
        ConversationAppService service = service();
        List<String> peers = List.of("u2", "u3", "u4");
        for (int i = 0; i < peers.size(); i++) {
            String peer = peers.get(i);
            String conv = ConvIds.single("u1", peer);
            deliver(service, textMsg("m" + i, conv, peer, 1, 100 + i, "msg" + i));
        }
        jdbc.update("INSERT INTO user_timeline(user_id,user_seq,event_type,msg_id,event_bytes,event_time) "
                + "VALUES('u1',7,10,'baseline','x',1)");

        ConversationPageDto first = service.listConversations("u1", null, 2);
        ConversationPageDto second = service.listConversations("u1", first.nextCursor(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.maxUserSeq()).isEqualTo(7);
        assertThat(second.items()).hasSize(1);
        assertThat(first.badgeCount()).isEqualTo(3);
        assertThat(ConversationSnapshotDto.class.getRecordComponents())
                .extracting(component -> component.getName())
                .contains("targetId", "unreadCount", "mentionMe", "pinned", "muted", "draft",
                        "maxConvSeq", "readConvSeq", "readOnly");
    }

    @Test
    void deleteKeepsMessagesAndNewMessageResetsConversationState() {
        ConversationAppService service = service();
        String convId = ConvIds.single("u1", "u2");
        deliver(service, textMsg("m1", convId, "u2", 1, 100, "before"));
        service.updateConversation("u1", convId, new UpdateConversationRequest(true, true));

        service.deleteConversation("u1", convId);
        assertThat(service.listConversations("u1", null, 20).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conv_timeline WHERE conv_id=?", Integer.class, convId))
                .isEqualTo(1);

        deliver(service, textMsg("m2", convId, "u2", 2, 200, "after"));
        assertThat(service.listConversations("u1", null, 20).items()).singleElement().satisfies(item -> {
            assertThat(item.pinned()).isFalse();
            assertThat(item.muted()).isFalse();
            assertThat(item.unreadCount()).isEqualTo(1);
            assertThat(item.lastMessageDigest()).isEqualTo("after");
        });
    }

    @Test
    void markReadIsMonotonicAndClearsUnreadMention() {
        ConversationAppService service = service();
        seedGroup("g1", "grp", List.of("u1", "u2", "u3"));
        String convId = ConvIds.group("g1");
        service.createGroupConversation("app1", "g1", convId, "grp", "",
                List.of("u1", "u2", "u3"), "u2", 50);
        deliver(service, groupMsg("m1", convId, "u2", 1, 100, "hi @Alice", List.of("u1")));

        assertThat(service.listConversations("u1", null, 20).items()).singleElement().satisfies(item -> {
            assertThat(item.unreadCount()).isEqualTo(1);
            assertThat(item.mentionMe()).isTrue();
        });
        assertThat(service.markRead("app1", "u1", convId, 1).readConvSeq()).isEqualTo(1);
        assertThat(service.listConversations("u1", null, 20).items()).singleElement().satisfies(item -> {
            assertThat(item.unreadCount()).isZero();
            assertThat(item.mentionMe()).isFalse();
        });
        assertThat(service.markRead("app1", "u1", convId, 0).readConvSeq()).isEqualTo(1);
        assertThat(events.events).anyMatch(event -> event.hasReadPosition());
        assertThat(events.events).anyMatch(event -> event.hasReadReceipt());
    }

    @Test
    void addingGroupMembersCreatesConversationStateForNewMember() {
        ConversationAppService service = service();
        seedGroup("g1", "grp", List.of("u1", "u2", "u3"));
        String convId = ConvIds.group("g1");
        service.createGroupConversation("app1", "g1", convId, "grp", "",
                List.of("u1", "u2"), "u1", 50);

        service.addGroupMembers(convId, List.of("u3"), 60);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_member WHERE conv_id=? AND user_id='u3'
                """, Integer.class, convId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_conversation WHERE conv_id=? AND user_id='u3'
                """, Integer.class, convId)).isEqualTo(1);
    }

    @Test
    void draftLastEditWinsAndSuccessfulMessageClearsSenderDraft() {
        ConversationAppService service = service();
        String convId = ConvIds.single("u1", "u2");
        deliver(service, textMsg("m1", convId, "u2", 1, 100, "hello"));

        assertThat(service.saveDraft("u1", convId, "newer", 200L).draft()).isEqualTo("newer");
        assertThat(service.saveDraft("u1", convId, "older", 100L).draft()).isEqualTo("newer");
        assertThat(service.saveDraft("u1", convId, "same-time-last-write", 200L).draft())
                .isEqualTo("same-time-last-write");

        deliver(service, textMsg("m2", convId, "u1", 2, 300, "sent"));
        assertThat(service.getConversation("u1", convId).draft()).isEmpty();
        assertThat(events.events).anyMatch(SyncEvent::hasDraft);
    }

    @Test
    void groupMembershipAndDissolutionRefreshReadOnlyWhileKeepingHistory() {
        ConversationAppService service = service();
        seedGroup("g1", "grp", List.of("u1", "u2"));
        String convId = ConvIds.group("g1");
        service.createGroupConversation("app1", "g1", convId, "grp", "",
                List.of("u1", "u2"), "u2", 50);
        jdbc.update("UPDATE group_member SET status='KICKED' WHERE group_id='g1' AND user_id='u1'");

        assertThat(service.getConversation("u1", convId).readOnly()).isTrue();
        jdbc.update("UPDATE group_member SET status='ACTIVE' WHERE group_id='g1' AND user_id='u1'");
        assertThat(service.refreshReadOnly("app1", "u1", convId).readOnly()).isFalse();
        jdbc.update("UPDATE chat_group SET status='DISSOLVED' WHERE group_id='g1'");
        assertThat(service.refreshReadOnly("app1", "u1", convId).readOnly()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_member WHERE conv_id=?", Integer.class, convId))
                .isEqualTo(2);
        assertThat(events.events).anyMatch(event -> event.hasConvState()
                && event.getConvState().getField() == ConvStateEvent.ConvStateField.CONV_STATE_FIELD_READ_ONLY);
    }

    private void seedGroup(String groupId, String name, List<String> members) {
        String convId = ConvIds.group(groupId);
        jdbc.update("""
                INSERT INTO chat_group(group_id,conv_id,name,avatar_url,owner_id,status,invite_verification,
                  member_count,created_at,updated_at)
                VALUES (?, ?, ?, '', ?, 'ACTIVE', TRUE, ?, 50, 50)
                """, groupId, convId, name, members.get(0), members.size());
        for (String member : members) {
            jdbc.update("""
                    INSERT INTO group_member(group_id,user_id,member_role,group_nickname,status,joined_at)
                    VALUES (?, ?, ?, '', 'ACTIVE', 50)
                    """, groupId, member, member.equals(members.get(0)) ? "OWNER" : "MEMBER");
        }
    }

    private ConversationAppService service() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("appConfigProvider", (AppConfigProvider) appId ->
                new AppFeatureConfigDto(true, true, false, 0, true, 3));
        return new ConversationAppService(repository, events, now::get, new ObjectMapper(),
                factory.getBeanProvider(AppConfigProvider.class),
                new DefaultListableBeanFactory().getBeanProvider(im.phantom.api.relation.RelationService.class),
                new DefaultListableBeanFactory().getBeanProvider(im.phantom.api.message.MessageService.class));
    }

    private void deliver(ConversationAppService service, Msg msg) {
        jdbc.update("""
                INSERT INTO conv_timeline(conv_id,conv_seq,msg_id,client_msg_id,sender_id,conv_type,
                  msg_type,msg_bytes,server_time,recalled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """, msg.getConvId(), msg.getConvSeq(), msg.getMsgId(), msg.getClientMsgId(),
                msg.getSenderId(), msg.getConvTypeValue(), msg.getMsgTypeValue(), msg.toByteArray(),
                msg.getServerTime());
        service.onNewMessage(msg);
    }

    private Msg textMsg(String msgId, String convId, String sender, long seq, long time, String text) {
        return baseMsg(msgId, convId, sender, seq, time, MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText(text))).build();
    }

    private Msg groupMsg(String msgId, String convId, String sender, long seq, long time,
                         String text, List<String> mentions) {
        return baseMsg(msgId, convId, sender, seq, time, MsgType.MSG_TYPE_TEXT)
                .addAllMentions(mentions)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText(text))).build();
    }

    private Msg.Builder baseMsg(String msgId, String convId, String sender, long seq, long time, MsgType type) {
        return Msg.newBuilder().setMsgId(msgId).setClientMsgId("c-" + msgId).setConvId(convId)
                .setConvType(ConvIds.isGroup(convId) ? ConvType.CONV_TYPE_GROUP : ConvType.CONV_TYPE_SINGLE)
                .setSenderId(sender).setMsgType(type).setConvSeq(seq).setServerTime(time);
    }

    private static final class CapturingWriter implements ConversationEventWriter {
        private final List<SyncEvent> events = new ArrayList<>();

        @Override
        public void append(String userId, String eventId, SyncEvent event) {
            events.add(event);
        }
    }
}
