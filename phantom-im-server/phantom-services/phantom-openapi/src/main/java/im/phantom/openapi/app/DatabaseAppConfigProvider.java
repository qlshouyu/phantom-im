package im.phantom.openapi.app;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import org.springframework.stereotype.Component;

/** 从应用配置表读取当前配置。 */
@Component
public class DatabaseAppConfigProvider implements AppConfigProvider {

    private final ApplicationRepository repository;

    public DatabaseAppConfigProvider(ApplicationRepository repository) {
        this.repository = repository;
    }

    @Override
    public AppFeatureConfigDto getConfig(String appId) {
        return repository.findById(appId)
                .map(ApplicationRepository.ApplicationRecord::features)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
    }
}
