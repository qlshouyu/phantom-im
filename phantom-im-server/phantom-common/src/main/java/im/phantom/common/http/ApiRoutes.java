package im.phantom.common.http;

/** MVP HTTP 路由清单；文档中的路径必须与此处常量保持一致。 */
public final class ApiRoutes {
    private ApiRoutes() {}

    public static final String OPENAPI_APPS = "/openapi/apps";
    public static final String OPENAPI_APP = "/openapi/apps/{appId}";
    public static final String OPENAPI_APP_SECRET_ROTATE = "/openapi/apps/{appId}/secrets/rotate";
    public static final String OPENAPI_APP_CONFIG = "/openapi/apps/{appId}/config";
    public static final String OPENAPI_USERS = "/openapi/users";
    public static final String OPENAPI_USER = "/openapi/users/{userId}";
    public static final String OPENAPI_USER_DISABLE = "/openapi/users/{userId}/disable";
    public static final String OPENAPI_USER_RESTORE = "/openapi/users/{userId}/restore";
    public static final String OPENAPI_USER_FORCE_LOGOUT = "/openapi/users/{userId}/force-logout";

    public static final String USERS_ME = "/users/me";
    public static final String USER = "/users/{userId}";
    public static final String FRIENDS = "/friends";
    public static final String FRIEND = "/friends/{friendId}";
    public static final String FRIEND_SETTINGS = "/friends/settings";
    public static final String FRIEND_REQUESTS = "/friends/requests";
    public static final String FRIEND_REQUEST = "/friends/requests/{requestId}";
    public static final String FRIEND_GROUPS = "/friends/groups";
    public static final String FRIEND_GROUP = "/friends/groups/{groupId}";
    public static final String BLACKLIST = "/blacklist";
    public static final String FRIEND_QR = "/friends/qr";
    public static final String FRIEND_QR_SCAN = "/friends/qr/scan";
    public static final String FRIEND_QR_REFRESH = "/friends/qr/refresh";

    public static final String GROUPS = "/groups";
    public static final String GROUP = "/groups/{groupId}";
    public static final String GROUP_MEMBERS = "/groups/{groupId}/members";
    public static final String GROUP_MEMBER_ROLE = "/groups/{groupId}/members/{userId}/role";
    public static final String GROUP_MEMBER_NICKNAME = "/groups/{groupId}/members/{userId}/nickname";
    public static final String GROUP_TRANSFER = "/groups/{groupId}/transfer";
    public static final String GROUP_LEAVE = "/groups/{groupId}/leave";
    public static final String GROUP_JOIN = "/groups/join";
    public static final String GROUP_JOIN_REQUEST = "/groups/{groupId}/join-requests/{requestId}";
    public static final String GROUP_INVITES = "/groups/{groupId}/invites";
    public static final String GROUP_ANNOUNCEMENT = "/groups/{groupId}/announcement";
    public static final String GROUP_MUTES = "/groups/{groupId}/mutes";

    public static final String CONVERSATIONS = "/conversations";
    public static final String CONVERSATION = "/conversations/{convId}";
    public static final String CONVERSATION_MESSAGES = "/conversations/{convId}/messages";
    public static final String CONVERSATION_READ_POSITIONS = "/conversations/{convId}/read-positions";
    public static final String CONVERSATION_DRAFT = "/conversations/{convId}/draft";
    public static final String CONVERSATION_REQUEST = "/conversations/{convId}/request";
    public static final String CONVERSATION_SEARCH = "/conversations/{convId}/search";
    public static final String MESSAGE_FORWARD = "/messages/forward";
    public static final String MESSAGE_RECALL = "/messages/{msgId}/recall";
    public static final String MESSAGE_LOCAL_DELETE = "/messages/{msgId}";
    public static final String CONVERSATION_MESSAGES_CLEAR = "/conversations/{convId}/messages";

    public static final String MEDIA_UPLOAD_INIT = "/media/upload/init";
    public static final String MEDIA_UPLOAD_PART = "/media/upload/{uploadId}/part/{partNumber}";
    public static final String MEDIA_UPLOAD_COMPLETE = "/media/upload/{uploadId}/complete";
    public static final String MEDIA = "/media/{mediaId}";
    public static final String MEDIA_CONTENT = "/media/{mediaId}/content";
    public static final String DEVICES = "/devices";
    public static final String DEVICE_LOGOUT = "/devices/{deviceId}/logout";
    public static final String PUSH_TOKENS = "/push/tokens";
    public static final String PUSH_SETTINGS = "/push/settings";
    public static final String SEARCH = "/search";
    public static final String SEARCH_SUGGEST = "/search/suggest";
    public static final String REPORTS = "/reports";
    public static final String APPEALS = "/appeals";
    public static final String ACCOUNT_EXPORT = "/account/export";
    public static final String ACCOUNT_MINOR_MODE = "/account/minor-mode";
    public static final String PRIVACY_LINKS = "/account/privacy-links";
    public static final String OPENAPI_RISK_SANCTIONS = "/openapi/risk/sanctions";
    public static final String OPENAPI_RISK_APPEALS = "/openapi/risk/appeals";
    public static final String OPENAPI_RISK_AUDIT = "/openapi/risk/audit";
    public static final String OPENAPI_RISK_REPORTS = "/openapi/risk/reports";
}
