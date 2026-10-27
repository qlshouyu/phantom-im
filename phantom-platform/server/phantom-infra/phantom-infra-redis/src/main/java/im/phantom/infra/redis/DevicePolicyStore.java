package im.phantom.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 应用设备策略缓存；权威值仍由应用配置表维护。 */
@Component
public class DevicePolicyStore {

    private final StringRedisTemplate redis;

    public DevicePolicyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void setMaxDevices(String appId, int maxDevices) {
        if (maxDevices <= 0) {
            throw new IllegalArgumentException("maxDevices 必须大于 0");
        }
        redis.opsForValue().set(key(appId), String.valueOf(maxDevices));
    }

    public int getMaxDevices(String appId, int defaultValue) {
        String value = redis.opsForValue().get(key(appId));
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    public void evict(String appId) {
        redis.delete(key(appId));
    }

    private String key(String appId) {
        return "policy:app:" + appId + ":max_devices";
    }
}
