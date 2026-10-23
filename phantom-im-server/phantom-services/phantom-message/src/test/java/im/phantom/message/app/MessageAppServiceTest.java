package im.phantom.message.app;

import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.infra.mq.TxMqProducer;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.SendMsgResp;
import im.phantom.proto.v1.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageAppServiceTest {

    private static final String SENDER = "u_alice";
    private static final String CONV = ConvIds.single(SENDER, "u_bob");

    @Mock
    private ConvTimelineDao dao;
    @Mock
    private SeqAllocator seqAllocator;
    @Mock
    private TxMqProducer producer;

    private MessageAppService service;

    @BeforeEach
    void setUp() {
        service = new MessageAppService(dao, seqAllocator, producer);
    }

    @Test
    void rejectsTextLongerThanFiveThousandBeforeAllocatingSequence() throws Exception {
        SendMsgReq req = request("c-long", "x".repeat(5001));

        assertThatThrownBy(() -> service.handleSend(SENDER, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCodes.MSG_TOO_LONG);

        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void duplicateClientMessageReturnsOriginalResultWithoutWritingAgain() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-1"))
                .thenReturn(new ConvTimelineDao.Existing("m_original", 42L, 123456L));

        SendMsgResp resp = service.handleSend(SENDER, request("c-1", "hello"));

        assertThat(resp.getMsgId()).isEqualTo("m_original");
        assertThat(resp.getConvSeq()).isEqualTo(42L);
        assertThat(resp.getServerTime()).isEqualTo(123456L);
        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void successfulSendAllocatesSequenceAndCommitsTransaction() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-2")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(9L);
        when(producer.sendInTransaction(eq("TOPIC_MSG_EVENT"), eq("new_msg"), eq(CONV), anyString(), any(), any()))
                .thenReturn(true);

        SendMsgResp resp = service.handleSend(SENDER, request("c-2", "hello"));

        assertThat(resp.getConvSeq()).isEqualTo(9L);
        assertThat(resp.getMsgId()).startsWith("m_");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(producer).sendInTransaction(eq("TOPIC_MSG_EVENT"), eq("new_msg"), eq(CONV), eq(resp.getMsgId()),
                body.capture(), any());
        assertThat(body.getValue()).isNotEmpty();
    }

    @Test
    void failedLocalTransactionIsNotReportedAsSuccess() throws Exception {
        when(dao.findByClientMsgId(SENDER, "c-3")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(10L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(false);
        when(dao.findByClientMsgId(SENDER, "c-3")).thenReturn(null);

        assertThatThrownBy(() -> service.handleSend(SENDER, request("c-3", "hello")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCodes.SERVER_ERROR);
    }

    private SendMsgReq request(String clientMsgId, String text) {
        return SendMsgReq.newBuilder()
                .setClientMsgId(clientMsgId)
                .setConvId(CONV)
                .setConvType(ConvType.CONV_TYPE_SINGLE)
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText(text)))
                .build();
    }
}
