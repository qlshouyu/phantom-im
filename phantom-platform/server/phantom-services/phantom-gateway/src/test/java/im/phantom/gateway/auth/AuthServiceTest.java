package im.phantom.gateway.auth;

import im.phantom.api.config.AppSecretProvider;
import im.phantom.api.config.AppStatusProvider;
import im.phantom.api.user.UserService;
import im.phantom.api.user.UserProfileDto;
import im.phantom.api.user.UserStatus;
import im.phantom.api.user.ImportUserRequest;
import im.phantom.api.user.ImportUserResponse;
import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.gateway.GatewayProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final String OLD = "01234567890123456789012345678901";
    private static final String NEW = "abcdefghijklmnopqrstuvwxyz123456";

    @Test
    void acceptsOldAndNewTokensDuringGraceAndRejectsOldAfterGrace() {
        MutableSecretProvider provider = new MutableSecretProvider(List.of(
                new AppSecretProvider.SecretMaterial("v2", NEW, null),
                new AppSecretProvider.SecretMaterial("v1", OLD, Instant.now().plusSeconds(60).toEpochMilli())
        ));
        AuthService service = authService(provider);
        String oldToken = token(OLD);
        String newToken = token(NEW);

        assertThat(service.verify(oldToken, "app1", "u1")).isEqualTo("u1");
        assertThat(service.verify(newToken, "app1", "u1")).isEqualTo("u1");

        provider.materials = List.of(new AppSecretProvider.SecretMaterial("v2", NEW, null));
        assertThatThrownBy(() -> service.verify(oldToken, "app1", "u1"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).code()).isEqualTo(ErrorCodes.AUTH_FAILED));
    }

    @Test
    void rejectsDisabledUserAtAuthentication() {
        MutableSecretProvider provider = new MutableSecretProvider(List.of(
                new AppSecretProvider.SecretMaterial("v1", NEW, null)
        ));
        AuthService service = authService(provider, new DisabledUserService());

        assertThatThrownBy(() -> service.verify(token(NEW), "app1", "u1"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).code()).isEqualTo(ErrorCodes.TOKEN_REVOKED));
    }

    @Test
    void rejectsInactiveApplication() {
        MutableSecretProvider provider = new MutableSecretProvider(List.of(
                new AppSecretProvider.SecretMaterial("v1", NEW, null)
        ));
        AuthService service = authService(provider, new StatusUserService(UserStatus.ACTIVE), appId -> false);

        assertThatThrownBy(() -> service.verify(token(NEW), "app1", "u1"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).code()).isEqualTo(ErrorCodes.AUTH_FAILED));
    }

    @Test
    void rejectsDeletedBannedAndCrossApplicationUsers() {
        MutableSecretProvider provider = new MutableSecretProvider(List.of(
                new AppSecretProvider.SecretMaterial("v1", NEW, null)
        ));
        assertCode(authService(provider, new StatusUserService(UserStatus.DELETED), appId -> true),
                ErrorCodes.TOKEN_REVOKED);
        assertCode(authService(provider, new StatusUserService(UserStatus.BANNED), appId -> true),
                ErrorCodes.ACCOUNT_BANNED);
        assertCode(authService(provider, new StatusUserService(null), appId -> true),
                ErrorCodes.AUTH_FAILED);
    }

    private AuthService authService(AppSecretProvider provider) {
        return authService(provider, null);
    }

    private AuthService authService(AppSecretProvider provider, UserService users) {
        return authService(provider, users, appId -> true);
    }

    private AuthService authService(AppSecretProvider provider, UserService users, AppStatusProvider appStatus) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("appSecretProvider", provider);
        if (users != null) {
            factory.registerSingleton("userService", users);
        }
        if (appStatus != null) {
            factory.registerSingleton("appStatusProvider", appStatus);
        }
        GatewayProperties props = new GatewayProperties();
        props.setAppId("app1");
        props.setAppSecret(NEW);
        return new AuthService(props, factory.getBeanProvider(AppSecretProvider.class),
                factory.getBeanProvider(UserService.class), factory.getBeanProvider(AppStatusProvider.class));
    }

    private void assertCode(AuthService service, int code) {
        assertThatThrownBy(() -> service.verify(token(NEW), "app1", "u1"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).code()).isEqualTo(code));
    }

    private String token(String secret) {
        return Jwts.builder()
                .claim("app_id", "app1")
                .claim("user_id", "u1")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static final class MutableSecretProvider implements AppSecretProvider {
        private List<SecretMaterial> materials;

        private MutableSecretProvider(List<SecretMaterial> materials) {
            this.materials = materials;
        }

        @Override
        public List<SecretMaterial> usableSecrets(String appId, long nowMillis) {
            return materials;
        }
    }

    private static final class DisabledUserService implements UserService {
        @Override public ImportUserResponse importUser(String appId, ImportUserRequest request) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String appId, String userId) { return true; }
        @Override public Optional<UserStatus> findStatus(String appId, String userId) { return Optional.of(UserStatus.DISABLED); }
        @Override public UserProfileDto getProfile(String appId, String userId) { throw new UnsupportedOperationException(); }
        @Override public UserProfileDto updateProfile(String appId, String userId, UpdateUserProfileRequest request, boolean sdkWrite) { throw new UnsupportedOperationException(); }
        @Override public void disable(String appId, String userId) { }
        @Override public void restore(String appId, String userId) { }
        @Override public void forceLogout(String appId, String userId, String deviceId) { }
        @Override public void delete(String appId, String userId) { }
    }

    private static final class StatusUserService implements UserService {
        private final UserStatus status;

        private StatusUserService(UserStatus status) {
            this.status = status;
        }

        @Override public ImportUserResponse importUser(String appId, ImportUserRequest request) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String appId, String userId) { return status != null; }
        @Override public Optional<UserStatus> findStatus(String appId, String userId) { return Optional.ofNullable(status); }
        @Override public UserProfileDto getProfile(String appId, String userId) { throw new UnsupportedOperationException(); }
        @Override public UserProfileDto updateProfile(String appId, String userId, UpdateUserProfileRequest request, boolean sdkWrite) { throw new UnsupportedOperationException(); }
        @Override public void disable(String appId, String userId) { }
        @Override public void restore(String appId, String userId) { }
        @Override public void forceLogout(String appId, String userId, String deviceId) { }
        @Override public void delete(String appId, String userId) { }
    }
}
