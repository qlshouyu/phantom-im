package im.phantom.risk.spi;

import java.util.Optional;

/** 内容审核 SPI；生产实现可接第三方审核服务。 */
public interface ContentModerationProvider {

    ModerationDecision moderate(ModerationRequest request);

    default Optional<ModerationDecision> review(ModerationRequest request) {
        return Optional.empty();
    }
}
