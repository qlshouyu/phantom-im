package im.phantom.gateway.device;

import im.phantom.api.device.DeviceInfoDto;
import im.phantom.common.http.ApiResponse;
import im.phantom.common.http.ApiRoutes;
import im.phantom.common.http.HttpAuthAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DeviceController {
    private final DeviceRegistryService devices;

    public DeviceController(DeviceRegistryService devices) {
        this.devices = devices;
    }

    @GetMapping(ApiRoutes.DEVICES)
    public ApiResponse<List<DeviceInfoDto>> list(
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.ok(devices.listOnline(userId), requestId);
    }

    @PostMapping(ApiRoutes.DEVICE_LOGOUT)
    public ApiResponse<Void> logout(@PathVariable("deviceId") String targetDeviceId,
            @RequestAttribute(HttpAuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "X-Device-Id", required = false) String currentDeviceId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        devices.remoteLogout(userId, currentDeviceId, targetDeviceId);
        return ApiResponse.ok(null, requestId);
    }
}
