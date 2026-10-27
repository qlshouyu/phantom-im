package im.phantom.risk.provider;

import im.phantom.common.risk.ContentModerationProvider;
import im.phantom.common.risk.ModerationDecision;
import im.phantom.common.risk.ModerationRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 将风险模块内部 provider 适配为跨模块通用 SPI。 */
@Component
public class CommonContentModerationAdapter implements ContentModerationProvider {

    private final im.phantom.risk.spi.ContentModerationProvider delegate;

    public CommonContentModerationAdapter(im.phantom.risk.spi.ContentModerationProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModerationDecision moderate(ModerationRequest request) {
        var result = delegate.moderate(new im.phantom.risk.spi.ModerationRequest(request.appId(),
                request.userId(), request.contentType(), request.text(), request.mediaId(), request.mimeType()));
        return new ModerationDecision(result.allowed(), result.reviewRequired(), result.reasonCode());
    }

    @Override
    public Optional<ModerationDecision> review(ModerationRequest request) {
        return delegate.review(new im.phantom.risk.spi.ModerationRequest(request.appId(),
                request.userId(), request.contentType(), request.text(), request.mediaId(), request.mimeType()))
                .map(result -> new ModerationDecision(result.allowed(), result.reviewRequired(), result.reasonCode()));
    }
}
