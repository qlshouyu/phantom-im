package im.phantom.search.app;

import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.DraftEvent;
import im.phantom.proto.v1.GroupEvent;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.TextContent;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.search.infra.SearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchAppServiceTest {
    @Mock private SearchRepository repository;

    @Test
    void returnsClassifiedResultsAndTotals() {
        when(repository.contacts("u1", "张", 20)).thenReturn(List.of(
                new SearchRepository.ContactRow("u2", "张三", "", "")));
        when(repository.groups("u1", "张", 20)).thenReturn(List.of(
                new SearchRepository.GroupRow("g1", "gr_g1", "张团队", "")));
        when(repository.accessibleMessages("u1", null, 2000)).thenReturn(List.of(
                message("m1", "si_a__b", "张三的消息", false)));
        when(repository.conversationTitle("u1", "si_a__b")).thenReturn("张三");

        var result = new SearchAppService(repository).search("u1", "张", 20);

        assertThat(result.contactTotal()).isEqualTo(1);
        assertThat(result.groupTotal()).isEqualTo(1);
        assertThat(result.messageTotal()).isEqualTo(1);
        assertThat(result.messages()).singleElement()
                .extracting(SearchDtos.MessageHit::convSeq).isEqualTo(3L);
    }

    @Test
    void recalledMessagesOnlyReturnTombstoneSemantics() {
        when(repository.accessibleMessages("u1", "si_a__b", 2000)).thenReturn(List.of(
                message("m1", "si_a__b", "原文", true)));
        var service = new SearchAppService(repository);

        assertThat(service.searchConversation("u1", "si_a__b", "原文", 20).total()).isZero();
        var tombstone = service.searchConversation("u1", "si_a__b", "撤回", 20);
        assertThat(tombstone.messages()).singleElement()
                .satisfies(hit -> assertThat(hit.recalled()).isTrue());
    }

    @Test
    void oneMessageOnlyUpdatesItsDocumentForParticipants() {
        when(repository.appIdForUser("u1")).thenReturn("app");
        when(repository.participantIds("si_u1__u2")).thenReturn(List.of("u1", "u2"));

        new SearchAppService(repository).indexMessage(message("m1", "si_u1__u2", "hello", false));

        verify(repository, times(2)).upsertDocument(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(repository, never()).contacts(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).accessibleMessages(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void draftAndReadEventsNeverTriggerUserReindex() throws Exception {
        SearchAppService service = spy(new SearchAppService(repository));
        SyncEvent draft = SyncEvent.newBuilder().setDraft(DraftEvent.newBuilder()
                .setConvId("si_u1__u2").setDraft("draft").setEditedAt(1)).build();

        service.onSyncEvent(envelope("u1", draft));

        verify(service, never()).reindexUser(anyString());
        verifyNoInteractions(repository);
    }

    @Test
    void relationAndGroupExitDeleteOnlyAffectedDocuments() throws Exception {
        SearchAppService service = new SearchAppService(repository);
        SyncEvent deleted = SyncEvent.newBuilder().setRelation(RelationEvent.newBuilder()
                .setType(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_FRIEND_DELETED)
                .setPeerId("u2")).build();
        service.onSyncEvent(envelope("u1", deleted));
        verify(repository).deleteContactDocument("u1", "u2");

        when(repository.conversationIdForGroup("g1")).thenReturn("gr_g1");
        SyncEvent left = SyncEvent.newBuilder().setGroup(GroupEvent.newBuilder()
                .setType(GroupEvent.GroupEventType.GROUP_EVENT_TYPE_MEMBER_LEFT)
                .setGroupId("g1")).build();
        service.onSyncEvent(envelope("u1", left));
        verify(repository).deleteGroupDocument("u1", "g1");
        verify(repository).deleteConversationDocumentsForOwner("u1", "gr_g1");
    }

    private Msg message(String id, String convId, String text, boolean recalled) {
        return Msg.newBuilder().setMsgId(id).setConvId(convId).setConvType(ConvType.CONV_TYPE_SINGLE)
                .setSenderId("u1")
                .setMsgType(MsgType.MSG_TYPE_TEXT).setContent(MsgContent.newBuilder()
                        .setText(TextContent.newBuilder().setText(text))).setConvSeq(3)
                .setServerTime(1).setRecalled(recalled).build();
    }

    private byte[] envelope(String userId, SyncEvent event) {
        return SyncEventEnvelope.newBuilder().setUserId(userId).setEventId("e1").setEvent(event)
                .build().toByteArray();
    }
}
