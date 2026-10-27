package im.phantom.push.app;

import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.TextContent;
import im.phantom.push.provider.LocalPushProvider;
import im.phantom.push.spi.PushDeliveryException;
import im.phantom.push.spi.PushProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.ArrayDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfflinePushServiceTest {
    @Mock private JdbcPushRepository repository;

    @Test
    void disabledPushProducesNoProviderCall() {
        LocalPushProvider provider = new LocalPushProvider();
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(
                false, true, "", ""));
        new OfflinePushService(repository, provider).notifyOffline("u2", message(List.of()));
        assertThat(provider.sentMessages()).isEmpty();
    }

    @Test
    void mutedConversationSuppressesNormalMessage() {
        LocalPushProvider provider = new LocalPushProvider();
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(
                true, true, "", ""));
        when(repository.muted("u2", "si_a__b")).thenReturn(true);
        new OfflinePushService(repository, provider).notifyOffline("u2", message(List.of()));
        assertThat(provider.sentMessages()).isEmpty();
    }

    @Test
    void mentionPenetratesMuteAndUsesPrivacyPreview() {
        LocalPushProvider provider = new LocalPushProvider();
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(
                true, false, "", ""));
        when(repository.muted("u2", "si_a__b")).thenReturn(true);
        when(repository.displayName("u1")).thenReturn("Alice");
        when(repository.activeTokens("u2")).thenReturn(List.of(
                new JdbcPushRepository.TokenRecord("t1", "token", "LOCAL")));
        when(repository.claimDelivery(any(), any(), any(), any(), any(), anyLong())).thenReturn(true);
        new OfflinePushService(repository, provider).notifyOffline("u2", message(List.of("u2")));
        assertThat(provider.sentMessages()).singleElement().satisfies(push -> {
            assertThat(push.title()).isEqualTo("Alice");
            assertThat(push.body()).isEqualTo("你收到一条新消息");
            assertThat(push.body()).doesNotContain("hello");
            assertThat(push.collapseKey()).isEqualTo("conv:si_a__b");
            assertThat(push.data()).containsEntry("conv_id", "si_a__b")
                    .containsEntry("deep_link", "phantom://conversation/si_a__b")
                    .containsKey("badge");
        });
    }

    @Test
    void invalidProviderTokenIsRemovedAfterFailure() {
        PushProvider invalid = message -> { throw PushDeliveryException.invalidToken("gone"); };
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(true, true, "", ""));
        when(repository.activeTokens("u2")).thenReturn(List.of(
                new JdbcPushRepository.TokenRecord("t1", "token", "APNS")));
        when(repository.claimDelivery(any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        new OfflinePushService(repository, invalid).notifyOffline("u2", message(List.of()));

        verify(repository).invalidateToken(eq("t1"), anyLong());
        verify(repository).failed(any(), anyLong());
    }

    @Test
    void retryableProviderFailureIsRescheduledWithStablePayload() {
        PushProvider failing = message -> { throw PushDeliveryException.retryable("temporary"); };
        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(true, true, "", ""));
        when(repository.activeTokens("u2")).thenReturn(List.of(
                new JdbcPushRepository.TokenRecord("t1", "token", "FCM")));
        when(repository.claimDelivery(any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        new OfflinePushService(repository, failing, scheduler, null).notifyOffline("u2", message(List.of()));

        verify(repository).retryScheduled(any(), anyLong());
        verify(scheduler).schedule(any(Runnable.class), eq(1L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void retryableFailuresEventuallyMoveToDeadLetter() {
        PushProvider failing = message -> { throw PushDeliveryException.retryable("still unavailable"); };
        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        ArrayDeque<Runnable> retries = new ArrayDeque<>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(invocation -> {
            retries.add(invocation.getArgument(0));
            return null;
        });
        when(repository.settings("u2")).thenReturn(new JdbcPushRepository.Settings(true, true, "", ""));
        when(repository.activeTokens("u2")).thenReturn(List.of(
                new JdbcPushRepository.TokenRecord("t1", "token", "FCM")));
        when(repository.claimDelivery(any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        new OfflinePushService(repository, failing, scheduler, null).notifyOffline("u2", message(List.of()));
        while (!retries.isEmpty()) retries.removeFirst().run();

        verify(repository).dead(any(), anyLong());
    }

    private Msg message(List<String> mentions) {
        return Msg.newBuilder().setMsgId("m1").setConvId("si_a__b")
                .setConvType(ConvType.CONV_TYPE_SINGLE).setSenderId("u1")
                .setMsgType(MsgType.MSG_TYPE_TEXT).setContent(MsgContent.newBuilder()
                        .setText(TextContent.newBuilder().setText("hello")))
                .addAllMentions(mentions).setServerTime(1).build();
    }
}
