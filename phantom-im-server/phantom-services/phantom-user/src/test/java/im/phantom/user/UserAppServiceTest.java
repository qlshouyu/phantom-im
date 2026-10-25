package im.phantom.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.config.AppFeatureConfigDto;
import im.phantom.api.sync.UserSyncEventWriter;
import im.phantom.api.user.ImportUserRequest;
import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.api.user.UserStatus;
import im.phantom.common.BizException;
import im.phantom.proto.v1.KickPush;
import im.phantom.proto.v1.ProfileEvent;
import im.phantom.proto.v1.SyncEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAppServiceTest {

    private JdbcTemplate jdbc;
    private RecordingKicker kicker;
    private CapturingWriter writer;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:user-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE app_user(user_id VARCHAR(64) PRIMARY KEY, app_id VARCHAR(64),
                  external_user_id VARCHAR(64), status VARCHAR(24), minor_mode BOOLEAN,
                  created_at BIGINT, updated_at BIGINT, deleted_at BIGINT,
                  UNIQUE(app_id, external_user_id))
                """);
        jdbc.execute("""
                CREATE TABLE user_profile(user_id VARCHAR(64) PRIMARY KEY, nickname VARCHAR(64),
                  avatar_url VARCHAR(1024), ext_json VARCHAR(4096), display_snapshot VARCHAR(64), updated_at BIGINT)
                """);
        jdbc.execute("CREATE TABLE group_member(group_id VARCHAR(64), user_id VARCHAR(64), status VARCHAR(24), left_at BIGINT)");
        jdbc.execute("CREATE TABLE conversation_member(conv_id VARCHAR(128), user_id VARCHAR(64), left_at BIGINT)");
        kicker = new RecordingKicker();
        writer = new CapturingWriter();
    }

    @Test
    void duplicateImportReturnsSamePhantomIdAndCreatesOnlyOneRow() {
        ImportUserRequest request = new ImportUserRequest("U1001", "小明", "https://a/1.png", Map.of("vip", "1"));
        UserAppService service = service(null);

        var first = service.importUser("app1", request);
        var second = service.importUser("app1", request);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void profileUpdatePersistsAndPublishesProfileEvent() {
        UserAppService service = service(null);
        var imported = service.importUser("app1", new ImportUserRequest("U1002", "Old", null, Map.of()));

        var updated = service.updateProfile("app1", imported.userId(),
                new UpdateUserProfileRequest("New", "https://a/new.png", Map.of("vip", "2")), false);

        assertThat(updated.nickname()).isEqualTo("New");
        assertThat(service.getProfile("app1", imported.userId()).ext()).containsEntry("vip", "2");
        assertThat(writer.event.getProfile().getType()).isEqualTo(ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_MY_PROFILE);
        assertThat(writer.event.getProfile().getChangedMap()).containsEntry("nickname", "New");
    }

    @Test
    void sdkProfileWriteIsRejectedWhenApplicationDisablesIt() {
        var config = new AppFeatureConfigDto(true, true, false, 0, false, 3);
        UserAppService service = service(config);
        var imported = service.importUser("app1", new ImportUserRequest("U1003", "Old", null, Map.of()));

        assertThatThrownBy(() -> service.updateProfile("app1", imported.userId(),
                new UpdateUserProfileRequest("New", null, Map.of()), true))
                .isInstanceOf(BizException.class);
    }

    @Test
    void disableRestoreForceLogoutAndDeleteUpdateStatusAndSessions() {
        UserAppService service = service(null);
        var imported = service.importUser("app1", new ImportUserRequest("U1004", "Alice", null, Map.of()));
        String userId = imported.userId();
        jdbc.update("INSERT INTO group_member(group_id,user_id,status) VALUES('g1',?,'ACTIVE')", userId);
        jdbc.update("INSERT INTO conversation_member(conv_id,user_id) VALUES('c1',?)", userId);

        service.disable("app1", userId);
        assertThat(service.findStatus("app1", userId)).contains(UserStatus.DISABLED);
        assertThat(kicker.reasons).containsExactly(KickPush.KickReason.KICK_REASON_TOKEN_REVOKED);

        service.restore("app1", userId);
        assertThat(service.findStatus("app1", userId)).contains(UserStatus.ACTIVE);

        service.forceLogout("app1", userId, "d1");
        assertThat(kicker.lastDevice).isEqualTo("d1");
        assertThat(kicker.reasons.getLast()).isEqualTo(KickPush.KickReason.KICK_REASON_REMOTE_LOGOUT);

        service.delete("app1", userId);
        assertThat(service.findStatus("app1", userId)).contains(UserStatus.DELETED);
        assertThat(service.getProfile("app1", userId).nickname()).isEqualTo("已注销用户");
        assertThat(jdbc.queryForObject("SELECT status FROM group_member WHERE user_id=?", String.class, userId))
                .isEqualTo("LEFT");
        assertThat(jdbc.queryForObject("SELECT left_at FROM conversation_member WHERE user_id=?", Long.class, userId))
                .isNotNull();
    }

    @Test
    void deletedExternalIdCanBeImportedAsNewUser() {
        UserAppService service = service(null);
        var first = service.importUser("app1", new ImportUserRequest("U1005", "Alice", null, Map.of()));
        service.delete("app1", first.userId());

        var second = service.importUser("app1", new ImportUserRequest("U1005", "Alice2", null, Map.of()));

        assertThat(second.created()).isTrue();
        assertThat(second.userId()).isNotEqualTo(first.userId());
    }

    @Test
    void rejectsInvalidExternalUserId() {
        UserAppService service = service(null);

        assertThatThrownBy(() -> service.importUser("app1",
                new ImportUserRequest("bad id!", "x", null, Map.of())))
                .isInstanceOf(BizException.class);
    }

    private UserAppService service(AppFeatureConfigDto config) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (config != null) {
            factory.registerSingleton("appConfigProvider", (AppConfigProvider) appId -> config);
        }
        return new UserAppService(new JdbcUserRepository(jdbc), new ObjectMapper(), () -> 100L,
                factory.getBeanProvider(AppConfigProvider.class), kicker, writer);
    }

    private static final class RecordingKicker implements UserSessionKicker {
        private final List<KickPush.KickReason> reasons = new ArrayList<>();
        private String lastDevice;

        @Override
        public void kickAll(String userId, KickPush.KickReason reason) {
            reasons.add(reason);
        }

        @Override
        public void kickDevice(String userId, String deviceId, KickPush.KickReason reason) {
            lastDevice = deviceId;
            reasons.add(reason);
        }
    }

    private static final class CapturingWriter implements UserSyncEventWriter {
        private SyncEvent event;

        @Override
        public long append(String userId, String eventId, SyncEvent event) {
            this.event = event;
            return 1L;
        }
    }
}
