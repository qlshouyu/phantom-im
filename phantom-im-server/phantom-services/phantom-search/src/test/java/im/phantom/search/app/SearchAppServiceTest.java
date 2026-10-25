package im.phantom.search.app;

import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.TextContent;
import im.phantom.search.infra.SearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    private Msg message(String id, String convId, String text, boolean recalled) {
        return Msg.newBuilder().setMsgId(id).setConvId(convId).setConvType(ConvType.CONV_TYPE_SINGLE)
                .setMsgType(MsgType.MSG_TYPE_TEXT).setContent(MsgContent.newBuilder()
                        .setText(TextContent.newBuilder().setText(text))).setConvSeq(3)
                .setServerTime(1).setRecalled(recalled).build();
    }
}
