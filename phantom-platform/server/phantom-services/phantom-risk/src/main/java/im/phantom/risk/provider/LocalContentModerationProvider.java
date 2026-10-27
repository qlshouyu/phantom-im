package im.phantom.risk.provider;

import im.phantom.risk.spi.ContentModerationProvider;
import im.phantom.risk.spi.ModerationDecision;
import im.phantom.risk.spi.ModerationRequest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 本地规则实现，仅用于开发和测试，不应视为生产级内容审核。 */
public class LocalContentModerationProvider implements ContentModerationProvider {

    private final List<String> blockedKeywords;
    private final boolean strictMode;

    public LocalContentModerationProvider(List<String> blockedKeywords, boolean strictMode) {
        this.blockedKeywords = List.copyOf(blockedKeywords);
        this.strictMode = strictMode;
    }

    @Override
    public ModerationDecision moderate(ModerationRequest request) {
        String text = request.text() == null ? "" : request.text().toLowerCase(Locale.ROOT);
        for (String keyword : blockedKeywords) {
            if (!keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return ModerationDecision.block("local.keyword");
            }
        }
        if (strictMode && request.mimeType() != null && request.mimeType().startsWith("image/")) {
            return ModerationDecision.review("local.image.review");
        }
        return ModerationDecision.allow();
    }

    @Override
    public Optional<ModerationDecision> review(ModerationRequest request) {
        if (request.mimeType() != null && request.mimeType().equalsIgnoreCase("image/svg+xml")) {
            return Optional.of(ModerationDecision.block("local.image.svg"));
        }
        return Optional.of(ModerationDecision.allow());
    }
}
