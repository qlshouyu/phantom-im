package im.phantom.openapi.app;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** 从应用配置表读取当前配置。 */
@Component
public class DatabaseAppConfigProvider implements AppConfigProvider {

    private final ApplicationRepository repository;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DatabaseAppConfigProvider(ApplicationRepository repository) {
        this(repository, 2_000L, System::nanoTime);
    }

    @Autowired
    public DatabaseAppConfigProvider(ApplicationRepository repository,
                                     @Value("${phantom.app-config.cache-ttl-ms:2000}") long ttlMillis) {
        this(repository, ttlMillis, System::nanoTime);
    }

    DatabaseAppConfigProvider(ApplicationRepository repository, long ttlMillis, LongSupplier nanoClock) {
        this.repository = repository;
        this.ttlNanos = Math.max(0, ttlMillis) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    @Override
    public AppFeatureConfigDto getConfig(String appId) {
        long now = nanoClock.getAsLong();
        CacheEntry cached = cache.get(appId);
        if (cached != null && now < cached.expiresAtNanos()) {
            return cached.config();
        }
        AppFeatureConfigDto loaded = repository.findById(appId)
                .map(ApplicationRepository.ApplicationRecord::features)
                .orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "应用不存在"));
        cache.put(appId, new CacheEntry(loaded, now + ttlNanos));
        return loaded;
    }

    private record CacheEntry(AppFeatureConfigDto config, long expiresAtNanos) {}
}
