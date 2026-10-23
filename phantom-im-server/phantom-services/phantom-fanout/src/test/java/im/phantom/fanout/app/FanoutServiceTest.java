package im.phantom.fanout.app;

import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.common.ConvIds;
import im.phantom.fanout.infra.UserTimelineWriteDao;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FanoutServiceTest {

    private static final String ALICE = "u_alice";
    private static final String BOB = "u_bob";
    private static final String CONV = ConvIds.single(ALICE, BOB);

    @Mock
    private UserTimelineWriteDao dao;
    @Mock
    private SeqAllocator seqAllocator;
    @Mock
    private OnlineDeliveryService delivery;

    private FanoutService service;
    private Msg message;

    @BeforeEach
    void setUp() {
        service = new FanoutService(dao, seqAllocator, delivery);
        message = Msg.newBuilder()
                .setMsgId("m_1")
                .setClientMsgId("c_1")
                .setConvId(CONV)
                .setConvType(ConvType.CONV_TYPE_SINGLE)
                .setSenderId(ALICE)
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText("hello")))
                .setConvSeq(1L)
                .setServerTime(1000L)
                .build();
    }

    @Test
    void writesOneEventAndPushesEachParticipantOnce() {
        when(dao.exists(ALICE, "m_1")).thenReturn(false);
        when(dao.exists(BOB, "m_1")).thenReturn(false);
        when(seqAllocator.nextUserSeq(ALICE)).thenReturn(11L);
        when(seqAllocator.nextUserSeq(BOB)).thenReturn(22L);
        when(delivery.deliver(anyString(), any())).thenReturn(1);

        service.onNewMessage(message);

        verify(dao).insert(eq(ALICE), eq(11L), eq(10), eq("m_1"), any(byte[].class), eq(1000L));
        verify(dao).insert(eq(BOB), eq(22L), eq(10), eq("m_1"), any(byte[].class), eq(1000L));
        verify(delivery).deliver(eq(ALICE), any());
        verify(delivery).deliver(eq(BOB), any());
    }

    @Test
    void duplicateConsumptionDoesNotAllocateSequenceOrPushAgain() {
        when(dao.exists(ALICE, "m_1")).thenReturn(true);
        when(dao.exists(BOB, "m_1")).thenReturn(true);

        service.onNewMessage(message);

        verify(seqAllocator, never()).nextUserSeq(anyString());
        verify(delivery, never()).deliver(anyString(), any());
    }

    @Test
    void concurrentDuplicateInsertIsSwallowedAsIdempotentRetry() {
        when(dao.exists(anyString(), eq("m_1"))).thenReturn(false);
        when(seqAllocator.nextUserSeq(anyString())).thenReturn(1L);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(dao).insert(anyString(), anyLong(), anyInt(), anyString(), any(byte[].class), anyLong());

        service.onNewMessage(message);

        verify(delivery, never()).deliver(anyString(), any());
    }
}
