package im.phantom.gateway.device;

import im.phantom.api.device.DeviceInfoDto;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.BizException;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.gateway.ws.ConnectionRegistry;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.KickPush;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 设备注册、在线设备列表和远程下线。 */
@Service
public class DeviceRegistryService {

    private final JdbcTemplate jdbc;
    private final RouteTableService routes;
    private final ConnectionRegistry connections;

    public DeviceRegistryService(JdbcTemplate jdbc, RouteTableService routes, ConnectionRegistry connections) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.connections = connections;
    }

    public void onAuthenticated(String userId, String deviceId, int deviceType, String platform, long now) {
        jdbc.update("""
                INSERT INTO device_registry(user_id, device_id, device_type, platform, device_name,
                    login_at, last_active_at, location, foreground)
                VALUES (?, ?, ?, ?, '', ?, ?, '', TRUE)
                ON DUPLICATE KEY UPDATE device_type=VALUES(device_type), platform=VALUES(platform),
                    login_at=VALUES(login_at), last_active_at=VALUES(last_active_at), foreground=TRUE
                """, userId, deviceId, deviceType, platform == null ? "" : platform, now, now);
    }

    public List<DeviceInfoDto> listOnline(String userId) {
        return routes.query(userId).stream().map(this::toDto).toList();
    }

    public void remoteLogout(String userId, String currentDeviceId, String targetDeviceId) {
        if (targetDeviceId == null || targetDeviceId.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "目标设备不能为空");
        }
        if (targetDeviceId.equals(currentDeviceId)) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "不能远程下线当前设备");
        }
        RouteEntry target = routes.query(userId).stream()
                .filter(route -> targetDeviceId.equals(route.deviceId()))
                .findFirst().orElseThrow(() -> new BizException(ErrorCodes.INVALID_PARAM, "设备不在线"));
        connections.send(target.connId(), Envelopes.push(Command.COMMAND_KICK_PUSH,
                KickPush.newBuilder().setReason(KickPush.KickReason.KICK_REASON_REMOTE_LOGOUT).build()));
        routes.remove(userId, target.deviceType(), target.connId());
        connections.close(target.connId());
        jdbc.update("UPDATE device_registry SET foreground=FALSE, last_active_at=? " +
                "WHERE user_id=? AND device_id=?", System.currentTimeMillis(), userId, targetDeviceId);
    }

    private DeviceInfoDto toDto(RouteEntry route) {
        try {
            return jdbc.query("SELECT platform, device_name, login_at, last_active_at, location " +
                            "FROM device_registry WHERE user_id=? AND device_id=?",
                    rs -> rs.next() ? new DeviceInfoDto(route.deviceId(), route.deviceType(),
                            rs.getString("platform"), rs.getString("device_name"), rs.getLong("login_at"),
                            route.lastActive(), rs.getString("location"), route.foreground())
                            : new DeviceInfoDto(route.deviceId(), route.deviceType(), "", "", 0,
                            route.lastActive(), "", route.foreground()), route.userId(), route.deviceId());
        } catch (RuntimeException missing) {
            return new DeviceInfoDto(route.deviceId(), route.deviceType(), "", "", 0,
                    route.lastActive(), "", route.foreground());
        }
    }
}
