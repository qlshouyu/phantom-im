package im.phantom.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.sync.UserSyncEventWriter;
import im.phantom.api.user.ImportUserRequest;
import im.phantom.api.user.ImportUserResponse;
import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.api.user.UserProfileDto;
import im.phantom.api.user.UserService;
import im.phantom.api.user.UserStatus;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.Ids;
import im.phantom.common.time.TimeProvider;
import im.phantom.proto.v1.KickPush;
import im.phantom.proto.v1.ProfileEvent;
import im.phantom.proto.v1.SyncEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Phantom ID 导入、资料、状态与删除用例。 */
@Service
public class UserAppService implements UserService {

    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9_\\-@.]{1,64}");
    private static final TypeReference<Map<String, String>> EXT_TYPE = new TypeReference<>() {};

    private final UserRepository repository;
    private final ObjectMapper objectMapper;
    private final TimeProvider time;
    private final ObjectProvider<AppConfigProvider> configProvider;
    private final UserSessionKicker sessionKicker;
    private final UserSyncEventWriter syncWriter;

    public UserAppService(UserRepository repository, ObjectMapper objectMapper, TimeProvider time,
                          ObjectProvider<AppConfigProvider> configProvider,
                          UserSessionKicker sessionKicker, UserSyncEventWriter syncWriter) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.time = time;
        this.configProvider = configProvider;
        this.sessionKicker = sessionKicker;
        this.syncWriter = syncWriter;
    }

    @Override
    @Transactional
    public ImportUserResponse importUser(String appId, ImportUserRequest request) {
        validateImport(appId, request);
        var existing = repository.findByExternalUserId(appId, request.externalUserId());
        if (existing.isPresent()) {
            return new ImportUserResponse(existing.get(), false);
        }
        String userId = Ids.newUserId();
        String nickname = request.nickname() == null || request.nickname().isBlank()
                ? request.externalUserId() : request.nickname();
        try {
            repository.insert(appId, userId, request.externalUserId(), nickname,
                    request.avatarUrl() == null ? "" : request.avatarUrl(), json(request.ext()), time.nowMillis());
            return new ImportUserResponse(userId, true);
        } catch (DuplicateKeyException concurrentImport) {
            return repository.findByExternalUserId(appId, request.externalUserId())
                    .map(id -> new ImportUserResponse(id, false))
                    .orElseThrow(() -> concurrentImport);
        }
    }

    @Override
    public boolean exists(String appId, String userId) {
        return appId != null && userId != null && repository.exists(appId, userId);
    }

    @Override
    public Optional<UserStatus> findStatus(String appId, String userId) {
        return repository.findById(appId, userId).map(user -> parseStatus(user.status()));
    }

    @Override
    public UserProfileDto getProfile(String appId, String userId) {
        UserRepository.UserRecord user = requireUser(appId, userId);
        if (UserStatus.DELETED.name().equals(user.status())) {
            return new UserProfileDto(userId, appId, "已注销用户", "", Map.of(), UserStatus.DELETED.name());
        }
        return toProfile(user);
    }

    @Override
    @Transactional
    public UserProfileDto updateProfile(String appId, String userId, UpdateUserProfileRequest request, boolean sdkWrite) {
        requireActiveUser(appId, userId);
        if (sdkWrite && !sdkWriteAllowed(appId)) {
            throw new BizException(ErrorCodes.NO_PERMISSION, "应用已禁用 SDK 资料写入");
        }
        validateProfile(request);
        long now = time.nowMillis();
        String avatarUrl = request.avatarUrl() == null ? "" : request.avatarUrl();
        repository.updateProfile(userId, request.nickname(), avatarUrl, json(request.ext()), now);
        UserProfileDto updated = new UserProfileDto(userId, appId, request.nickname(), avatarUrl,
                request.ext(), UserStatus.ACTIVE.name());
        publishProfileEvent(userId, updated, now);
        return updated;
    }

    @Override
    @Transactional
    public void disable(String appId, String userId) {
        requireUser(appId, userId);
        repository.updateStatus(userId, UserStatus.DISABLED.name(), time.nowMillis());
        if (sessionKicker != null) {
            sessionKicker.kickAll(userId, KickPush.KickReason.KICK_REASON_TOKEN_REVOKED);
        }
    }

    @Override
    @Transactional
    public void restore(String appId, String userId) {
        requireUser(appId, userId);
        repository.updateStatus(userId, UserStatus.ACTIVE.name(), time.nowMillis());
    }

    @Override
    public void forceLogout(String appId, String userId, String deviceId) {
        requireUser(appId, userId);
        if (sessionKicker != null) {
            sessionKicker.kickDevice(userId, deviceId, KickPush.KickReason.KICK_REASON_REMOTE_LOGOUT);
        }
    }

    @Override
    @Transactional
    public void delete(String appId, String userId) {
        requireUser(appId, userId);
        long now = time.nowMillis();
        repository.markDeleted(userId, Ids.newId("deleted"), now);
        repository.anonymizeProfile(userId, now);
        repository.leaveGroupsAndConversations(userId, now);
        if (sessionKicker != null) {
            sessionKicker.kickAll(userId, KickPush.KickReason.KICK_REASON_TOKEN_REVOKED);
        }
    }

    private UserRepository.UserRecord requireUser(String appId, String userId) {
        return repository.findById(appId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.USER_NOT_FOUND, "用户不存在"));
    }

    private void requireActiveUser(String appId, String userId) {
        UserRepository.UserRecord user = requireUser(appId, userId);
        if (!UserStatus.ACTIVE.name().equals(user.status())) {
            throw new BizException(ErrorCodes.USER_NOT_FOUND, "用户不可用");
        }
    }

    private boolean sdkWriteAllowed(String appId) {
        AppConfigProvider provider = configProvider.getIfAvailable();
        if (provider == null) {
            return true;
        }
        try {
            return provider.getConfig(appId).sdkProfileWriteEnabled();
        } catch (BizException missingApp) {
            return true;
        }
    }

    private UserProfileDto toProfile(UserRepository.UserRecord user) {
        return new UserProfileDto(user.userId(), user.appId(), user.nickname(), user.avatarUrl(),
                parseExt(user.extJson()), user.status());
    }

    private void publishProfileEvent(String userId, UserProfileDto profile, long now) {
        if (syncWriter == null) {
            return;
        }
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("nickname", profile.nickname());
        changed.put("avatar_url", profile.avatarUrl());
        changed.putAll(profile.ext());
        SyncEvent event = SyncEvent.newBuilder()
                .setEventTime(now)
                .setProfile(ProfileEvent.newBuilder()
                        .setType(ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_MY_PROFILE)
                        .setTargetId(userId)
                        .putAllChanged(changed))
                .build();
        syncWriter.append(userId, Ids.newEventId(), event);
    }

    private void validateImport(String appId, ImportUserRequest request) {
        if (appId == null || appId.isBlank() || request == null || request.externalUserId() == null
                || !EXTERNAL_ID.matcher(request.externalUserId()).matches()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "external_user_id 格式非法");
        }
        if (request.nickname() != null && request.nickname().codePointCount(0, request.nickname().length()) > 20) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "昵称最多 20 字符");
        }
        if (request.avatarUrl() != null && request.avatarUrl().length() > 1024) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "头像 URL 过长");
        }
        validateExt(request.ext());
    }

    private void validateProfile(UpdateUserProfileRequest request) {
        if (request == null || request.nickname() == null || request.nickname().isBlank()
                || request.nickname().codePointCount(0, request.nickname().length()) > 20) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "昵称必须为 1-20 字符");
        }
        if (request.avatarUrl() != null && request.avatarUrl().length() > 1024) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "头像 URL 过长");
        }
        validateExt(request.ext());
    }

    private void validateExt(Map<String, String> ext) {
        if (ext != null && (ext.size() > 16 || ext.values().stream().anyMatch(v -> v.getBytes().length > 512))) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "ext 字段超限");
        }
    }

    private UserStatus parseStatus(String status) {
        try {
            return UserStatus.valueOf(status);
        } catch (Exception invalid) {
            return UserStatus.ACTIVE;
        }
    }

    private Map<String, String> parseExt(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, EXT_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String json(Map<String, String> ext) {
        try {
            return objectMapper.writeValueAsString(ext == null ? Map.of() : ext);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "ext 无法序列化");
        }
    }
}
