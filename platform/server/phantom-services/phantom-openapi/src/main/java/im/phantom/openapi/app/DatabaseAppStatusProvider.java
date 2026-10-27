package im.phantom.openapi.app;

import im.phantom.api.config.AppStatusProvider;
import org.springframework.stereotype.Component;

/** 从应用表读取 ACTIVE 状态。 */
@Component
public class DatabaseAppStatusProvider implements AppStatusProvider {

    private final ApplicationRepository repository;

    public DatabaseAppStatusProvider(ApplicationRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isActive(String appId) {
        return repository.findById(appId).map(app -> "ACTIVE".equals(app.status())).orElse(false);
    }
}
