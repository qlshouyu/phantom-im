package im.phantom.web.phantom;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ProxyPathPolicy {
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "users", "friends", "blacklist", "groups", "conversations", "messages", "media",
            "devices", "push", "search", "reports", "appeals", "account");

    public boolean isAllowed(String path) {
        if (path != null) path = path.split("\\?", 2)[0];
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("..") || path.startsWith("/openapi")) {
            return false;
        }
        String first = path.substring(1).split("/", 2)[0];
        return ALLOWED_ROOTS.contains(first);
    }
}
