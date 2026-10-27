package im.phantom.common.risk;

import java.util.Optional;

/** 与供应商无关的内容审核扩展点。 */
public interface ContentModerationProvider {
    ModerationDecision moderate(ModerationRequest request);

    /** 异步复审；空表示供应商未给出复审结论。 */
    default Optional<ModerationDecision> review(ModerationRequest request) {
        return Optional.empty();
    }
}
