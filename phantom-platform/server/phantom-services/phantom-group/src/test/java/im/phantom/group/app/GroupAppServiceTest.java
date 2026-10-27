package im.phantom.group.app;

import im.phantom.api.conversation.ConversationService;
import im.phantom.api.group.GroupDtos.CreateGroupRequest;
import im.phantom.api.group.GroupDtos.InviteMembersRequest;
import im.phantom.api.group.GroupDtos.JoinByInviteRequest;
import im.phantom.api.relation.FriendSummaryDto;
import im.phantom.api.relation.RelationService;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.group.infra.JdbcGroupRepository;
import im.phantom.group.infra.JdbcGroupRepository.GroupRecord;
import im.phantom.group.infra.JdbcGroupRepository.AnnouncementRecord;
import im.phantom.group.infra.JdbcGroupRepository.InviteRecord;
import im.phantom.group.infra.JdbcGroupRepository.MemberRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class GroupAppServiceTest {

    @Mock private JdbcGroupRepository repository;
    @Mock private ConversationService conversations;
    @Mock private RelationService relations;
    @Mock private GroupEventWriter events;
    private GroupAppService groups;
    private long now = 1000;

    @BeforeEach
    void setUp() {
        groups = new GroupAppService(repository, conversations, relations, events, () -> now);
    }

    @Test
    void createGroupRejectsFewerThanTwoFriends() {
        assertThatThrownBy(() -> groups.createGroup("app", "u1",
                new CreateGroupRequest("群", List.of("u2"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("2 名好友");
        verify(repository, never()).insertGroup(any());
    }

    @Test
    void createGroupValidatesFriendshipAndCreatesConversation() {
        when(relations.listFriends("u1")).thenReturn(List.of(
                friend("u2"), friend("u3")));
        when(repository.findGroup(anyString())).thenReturn(Optional.of(group()));

        groups.createGroup("app", "u1", new CreateGroupRequest("  产品群  ", List.of("u2", "u3")));

        verify(repository).insertGroup(any());
        verify(conversations).createGroupConversation(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList(), anyString(), any(Long.class));
    }

    @Test
    void ordinaryMemberCannotMentionAll() {
        when(repository.findGroup("g1")).thenReturn(Optional.of(group()));
        when(repository.findMember("g1", "u2")).thenReturn(Optional.of(member("MEMBER")));
        when(repository.hasPendingMute("g1", "u2", now)).thenReturn(false);

        assertThatThrownBy(() -> groups.authorizeMessage("app", "u2", "g1", List.of("ALL")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.NO_PERMISSION);
    }

    @Test
    void mutedMemberCannotSendButManagerCan() {
        when(repository.findGroup("g1")).thenReturn(Optional.of(group()));
        when(repository.findMember("g1", "u2")).thenReturn(Optional.of(member("MEMBER")));
        when(repository.findMember("g1", "admin")).thenReturn(Optional.of(member("ADMIN")));
        when(repository.hasPendingMute("g1", "u2", now)).thenReturn(true);

        assertThatThrownBy(() -> groups.authorizeMessage("app", "u2", "g1", List.of()))
                .extracting(error -> ((BizException) error).code()).isEqualTo(ErrorCodes.MUTED);
        groups.authorizeMessage("app", "admin", "g1", List.of());
    }

    @Test
    void repeatedInviteIsIdempotentAndEmitsOnlyOnce() {
        when(repository.findGroup("g1")).thenReturn(Optional.of(group()));
        when(repository.findMember("g1", "u1")).thenReturn(Optional.of(member("OWNER", "u1")));
        when(repository.findMember("g1", "u2")).thenReturn(Optional.empty(),
                Optional.of(member("MEMBER", "u2")));
        when(repository.activeMemberCount("g1")).thenReturn(3);
        when(repository.insertMember("g1", "u2", "MEMBER", now)).thenReturn(true);
        when(repository.activeMemberIds("g1")).thenReturn(List.of("u1", "u2"));

        groups.inviteMembers("app", "u1", "g1", new InviteMembersRequest(List.of("u2")));
        groups.inviteMembers("app", "u1", "g1", new InviteMembersRequest(List.of("u2")));

        verify(repository, times(1)).insertMember("g1", "u2", "MEMBER", now);
        verify(conversations).addGroupMembers("gr_g1", List.of("u2"), now);
        verify(events, times(2)).append(anyString(), anyString(), any());
    }

    @Test
    void kickedMemberCannotRejoinWithOldInviteToken() {
        when(repository.findActiveInvite(anyString(), eq(now))).thenReturn(Optional.of(
                new InviteRecord("i1", "g1", "hash", "LINK", "u1", 1, now + 1000)));
        when(repository.findGroup("g1")).thenReturn(Optional.of(group()));
        when(repository.findMember("g1", "u2")).thenReturn(Optional.of(
                new MemberRecord("g1", "u2", "MEMBER", "", "KICKED", 1, now, null, false)));

        assertThatThrownBy(() -> groups.joinByInvite("app", "u2", new JoinByInviteRequest("token")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.NO_PERMISSION);
        verify(repository, never()).insertMember(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void announcementFirstViewClearsUnreadAndNicknameEmitsSyncEvent() {
        AnnouncementRecord announcement = new AnnouncementRecord("g1", "公告内容", "u1", 2, now);
        when(repository.findGroup("g1")).thenReturn(Optional.of(group()));
        when(repository.findMember("g1", "u2")).thenReturn(Optional.of(member("MEMBER", "u2")));
        when(repository.announcement("g1")).thenReturn(Optional.of(announcement));
        when(repository.announcementUnread("g1", "u2", 2)).thenReturn(true);

        var detail = groups.getGroup("u2", "g1");
        assertThat(detail.announcementUnread()).isTrue();

        groups.getAnnouncement("u2", "g1");
        verify(repository).markAnnouncementRead("g1", "u2", 2, now);

        when(repository.findMember("g1", "u1")).thenReturn(Optional.of(member("OWNER", "u1")));
        when(repository.activeMemberIds("g1")).thenReturn(List.of("u1", "u2"));
        groups.updateGroupNickname("u1", "g1", "u2", "产品同学");
        verify(repository).updateGroupNickname("g1", "u2", "产品同学", now);
        verify(events, times(2)).append(anyString(), anyString(), any());
    }

    private FriendSummaryDto friend(String id) {
        return new FriendSummaryDto(id, id, "", id, "", false, List.of());
    }
    private GroupRecord group() {
        return new GroupRecord("g1", "gr_g1", "群", "", "u1", "ACTIVE", true, false, 3, 1, 1);
    }
    private MemberRecord member(String role) {
        return member(role, role.equals("ADMIN") ? "admin" : "u2");
    }
    private MemberRecord member(String role, String userId) {
        return new MemberRecord("g1", userId, role, "",
                "ACTIVE", 1, null, null, false);
    }
}
