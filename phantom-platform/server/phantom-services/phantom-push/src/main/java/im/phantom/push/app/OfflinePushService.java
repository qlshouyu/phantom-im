package im.phantom.push.app;

import im.phantom.api.route.OfflinePushNotifier;
import im.phantom.common.Ids;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgType;
import im.phantom.push.spi.PushMessage;
import im.phantom.push.spi.PushProvider;
import im.phantom.push.spi.PushDeliveryException;
import im.phantom.infra.observability.PlatformMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 离线推送决策树：总开关、免打扰、@穿透、隐私预览、幂等和深链。 */
@Service
public class OfflinePushService implements OfflinePushNotifier {

    private static final Logger log = LoggerFactory.getLogger(OfflinePushService.class);
    private final JdbcPushRepository repository;
    private final PushProvider provider;
    private final ScheduledExecutorService scheduler;
    private final PlatformMetrics metrics;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public OfflinePushService(JdbcPushRepository repository, PushProvider provider) {
        this(repository, provider, null, null);
    }

    @Autowired
    public OfflinePushService(JdbcPushRepository repository, PushProvider provider,
                              @Qualifier("pushScheduler") ScheduledExecutorService scheduler,
                              ObjectProvider<PlatformMetrics> metrics) {
        this.repository = repository;
        this.provider = provider;
        this.scheduler = scheduler;
        this.metrics = metrics == null ? null : metrics.getIfAvailable();
    }

    @Override
    public void notifyOffline(String receiverId, Msg message) {
        if (message.getRestricted() || message.getSenderId().equals(receiverId)) {
            return;
        }
        JdbcPushRepository.Settings settings = repository.settings(receiverId);
        if (!settings.pushEnabled()) {
            return;
        }
        boolean mentioned = message.getMentionsList().stream()
                .anyMatch(value -> value.equals(receiverId) || "ALL".equalsIgnoreCase(value));
        if (repository.muted(receiverId, message.getConvId()) && !mentioned) {
            return;
        }
        String sender = repository.displayName(message.getSenderId());
        boolean group = im.phantom.common.ConvIds.isGroup(message.getConvId());
        String title = group ? repository.groupName(message.getConvId()) : sender;
        String body = settings.previewEnabled()
                ? sender + "：" + digest(message) : "你收到一条新消息";
        if (mentioned && settings.previewEnabled()) {
            title = "[有人@我] " + title;
        }
        for (JdbcPushRepository.TokenRecord token : repository.activeTokens(receiverId)) {
            String deliveryId = Ids.newId("pd");
            long now = System.currentTimeMillis();
            if (!repository.claimDelivery(deliveryId, receiverId, message.getMsgId(),
                    message.getConvId(), token.tokenId(), now)) {
                continue;
            }
            PushMessage push = new PushMessage(token.token(), title, body,
                    "conv:" + message.getConvId(), Map.of(
                    "conv_id", message.getConvId(),
                    "msg_id", message.getMsgId(),
                    "badge", Integer.toString(repository.badgeCount(receiverId)),
                    "deep_link", "phantom://conversation/" + message.getConvId()));
            deliver(deliveryId, token, push, 0);
        }
    }

    private void deliver(String deliveryId, JdbcPushRepository.TokenRecord token,
                         PushMessage push, int attempt) {
        try {
            repository.delivered(deliveryId, provider.send(push), System.currentTimeMillis());
            if (metrics != null) metrics.recordPushDelivery(token.provider(), "DELIVERED");
        } catch (PushDeliveryException error) {
            if (error.invalidToken()) {
                repository.invalidateToken(token.tokenId(), System.currentTimeMillis());
            }
            if (error.retryable() && scheduler != null && attempt < MAX_RETRY_ATTEMPTS) {
                repository.retryScheduled(deliveryId, System.currentTimeMillis());
                long delay = 1L << attempt;
                scheduler.schedule(() -> deliver(deliveryId, token, push, attempt + 1), delay, TimeUnit.SECONDS);
            } else {
                if (error.retryable()) repository.dead(deliveryId, System.currentTimeMillis());
                else repository.failed(deliveryId, System.currentTimeMillis());
                if (metrics != null) metrics.recordPushDlq(token.provider());
            }
            log.warn("离线推送失败 token={} retryable={} invalid={}: {}", token.tokenId(),
                    error.retryable(), error.invalidToken(), error.getMessage());
        } catch (RuntimeException error) {
            repository.dead(deliveryId, System.currentTimeMillis());
            if (metrics != null) metrics.recordPushDlq(token.provider());
            log.warn("离线推送异常 token={}: {}", token.tokenId(), error.toString());
        }
    }

    private String digest(Msg message) {
        return switch (message.getMsgType()) {
            case MSG_TYPE_TEXT -> truncate(message.getContent().getText().getText());
            case MSG_TYPE_IMAGE -> "[图片]";
            case MSG_TYPE_VOICE -> "[语音]";
            case MSG_TYPE_VIDEO -> "[视频]";
            case MSG_TYPE_FILE -> "[文件]";
            case MSG_TYPE_EMOJI -> "[表情]";
            case MSG_TYPE_LOCATION -> "[位置]";
            case MSG_TYPE_CARD -> "[名片]";
            case MSG_TYPE_MERGED -> "[聊天记录]";
            default -> "[新消息]";
        };
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "[空消息]";
        return value.codePointCount(0, value.length()) <= 30 ? value
                : value.substring(0, value.offsetByCodePoints(0, 30)) + "…";
    }
}
