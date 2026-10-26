package im.phantom.message.app;

import im.phantom.api.relation.RelationService;
import im.phantom.api.relation.DirectMessagePolicy;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.common.BizException;
import im.phantom.common.ConvIds;
import im.phantom.common.ErrorCodes;
import im.phantom.common.risk.ContentModerationProvider;
import im.phantom.common.risk.ModerationDecision;
import im.phantom.common.risk.ModerationRequest;
import im.phantom.common.risk.UserSafetyChecker;
import im.phantom.infra.mq.TxMqProducer;
import im.phantom.infra.observability.PlatformMetrics;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.message.MessagePublishProperties;
import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.Msg;
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
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
    @Mock
    private ObjectProvider<RelationService> relationServices;
    @Mock
    private RelationService relationService;
    @Mock
    private ObjectProvider<ContentModerationProvider> moderationProviders;
    @Mock
    private ObjectProvider<AppConfigProvider> configProviders;

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
    void recordsCorrelatedSendStages() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(dao.findByClientMsgId(SENDER, "c-metrics")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(12L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        MessageAppService instrumented = new MessageAppService(dao, seqAllocator, producer,
                null, null, null, null, null, null, new PlatformMetrics(registry));

        instrumented.handleSend(SENDER, request("c-metrics", "hello"));

        assertThat(registry.get(PlatformMetrics.MESSAGE_STAGE_DURATION)
                .tag("stage", "send_total").tag("result", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(PlatformMetrics.MESSAGE_STAGE_DURATION)
                .tag("stage", "local_tx").timer().count()).isEqualTo(1);
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

    @Test
    void blacklistPolicyRejectsMessageBeforeAllocatingSequence() throws Exception {
        when(relationServices.getIfAvailable()).thenReturn(relationService);
        doThrow(new BizException(ErrorCodes.REJECTED_BY_PEER, "对方拒绝接收消息"))
                .when(relationService).evaluateDirectMessage("app1", SENDER, "u_bob", true);
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer, relationServices,
                null, null, null, null, null);

        assertThatThrownBy(() -> guarded.handleSend("app1", SENDER, request("c-blocked", "hello")))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.REJECTED_BY_PEER);
        verify(seqAllocator, never()).nextConvSeq(anyString());
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void loadsApplicationConfigOnceAndReusesSnapshotForRiskRelationAndBurnTtl() throws Exception {
        AppConfigProvider configProvider = org.mockito.Mockito.mock(AppConfigProvider.class);
        when(configProvider.getConfig("app")).thenReturn(
                new AppFeatureConfigDto(true, true, true, 30, true, 3, false));
        when(configProviders.getIfAvailable()).thenReturn(configProvider);
        when(relationServices.getIfAvailable()).thenReturn(relationService);
        when(relationService.evaluateDirectMessage("app", SENDER, "u_bob", true))
                .thenReturn(DirectMessagePolicy.ALLOWED);
        when(dao.findByClientMsgId(SENDER, "c-config-snapshot")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(13L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer, relationServices,
                configProviders, null, null, moderationProviders, null);

        guarded.handleSend("app", SENDER, request("c-config-snapshot", "hello"));

        verify(configProvider, times(1)).getConfig("app");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(producer).sendInTransaction(anyString(), anyString(), anyString(), anyString(), body.capture(), any());
        assertThat(Msg.parseFrom(body.getValue()).getBurnAfterReadTtlSeconds()).isEqualTo(30);
    }

    @Test
    void disabledRiskCheckSkipsModerationSafetyAndRiskQueries() throws Exception {
        AppConfigProvider configProvider = org.mockito.Mockito.mock(AppConfigProvider.class);
        when(configProvider.getConfig("app")).thenReturn(
                new AppFeatureConfigDto(true, true, false, 0, true, 3, false));
        when(configProviders.getIfAvailable()).thenReturn(configProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<UserSafetyChecker> safetyProviders = org.mockito.Mockito.mock(ObjectProvider.class);
        when(dao.findByClientMsgId(SENDER, "c-risk-off")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(14L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer,
                null, configProviders, null, null, moderationProviders, safetyProviders);

        guarded.handleSend("app", SENDER, request("c-risk-off", "hello"));

        verify(moderationProviders, never()).getIfAvailable();
        verify(safetyProviders, never()).getIfAvailable();
    }

    @Test
    void outboxModeDoesNotWaitForTransactionMqProducer() throws Exception {
        MessagePublishProperties properties = new MessagePublishProperties();
        properties.setPublishMode("outbox");
        MessagePersistenceService persistence = org.mockito.Mockito.mock(MessagePersistenceService.class);
        when(dao.findByClientMsgId(SENDER, "c-outbox")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(15L);
        MessageAppService outboxService = new MessageAppService(dao, seqAllocator, producer,
                null, null, null, null, null, null, null, properties, persistence);

        SendMsgResp response = outboxService.handleSend("app", SENDER, request("c-outbox", "hello"));

        assertThat(response.getConvSeq()).isEqualTo(15L);
        verify(persistence).persistWithOutbox(any(Msg.class));
        verify(producer, never()).sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void expiredBurnAfterReadHistoryReturnsTombstoneWithoutContent() {
        Msg expired = Msg.newBuilder().setMsgId("m_burn").setClientMsgId("c_burn")
                .setConvId(CONV).setConvType(ConvType.CONV_TYPE_SINGLE).setSenderId(SENDER)
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText("secret")))
                .setConvSeq(1).setServerTime(System.currentTimeMillis() - 10_000)
                .setBurnAfterReadTtlSeconds(1).build();
        when(dao.historyForUser(SENDER, CONV, Long.MAX_VALUE, 51)).thenReturn(List.of(expired));

        var page = service.history(SENDER, CONV, 0, 50);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.contentJson()).isEqualTo("{}");
            assertThat(item.burnAfterReadTtlSeconds()).isEqualTo(1);
        });
    }

    @Test
    void asyncReviewBlocksSuspiciousMediaAndTriggersSystemRecall() throws Exception {
        CountDownLatch reviewed = new CountDownLatch(1);
        ContentModerationProvider provider = new ContentModerationProvider() {
            @Override public ModerationDecision moderate(ModerationRequest request) {
                return ModerationDecision.review("image.review");
            }
            @Override public Optional<ModerationDecision> review(ModerationRequest request) {
                reviewed.countDown();
                return Optional.of(ModerationDecision.block("image.rejected"));
            }
        };
        when(moderationProviders.getIfAvailable()).thenReturn(provider);
        AppConfigProvider configProvider = org.mockito.Mockito.mock(AppConfigProvider.class);
        when(configProvider.getConfig("app")).thenReturn(
                new AppFeatureConfigDto(true, true, false, 0, true, 3, true));
        when(configProviders.getIfAvailable()).thenReturn(configProvider);
        MessageAppService guarded = new MessageAppService(dao, seqAllocator, producer,
                null, configProviders, null, null, moderationProviders, null);
        when(dao.findByClientMsgId(SENDER, "c-review")).thenReturn(null);
        when(seqAllocator.nextConvSeq(CONV)).thenReturn(11L);
        when(producer.sendInTransaction(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(true);
        when(dao.findByMsgId(anyString())).thenAnswer(invocation -> Msg.newBuilder()
                .setMsgId(invocation.getArgument(0)).setConvId(CONV)
                .setSenderId(SENDER).setServerTime(1000).build());
        when(dao.markRecalled(any(), anyLong())).thenReturn(true);

        guarded.handleSend("app", SENDER, request("c-review", "image"));

        assertThat(reviewed.await(3, TimeUnit.SECONDS)).isTrue();
        verify(dao, org.mockito.Mockito.timeout(3000)).markRecalled(any(), anyLong());
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
