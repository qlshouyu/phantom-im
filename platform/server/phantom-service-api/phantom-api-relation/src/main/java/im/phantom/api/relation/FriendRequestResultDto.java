package im.phantom.api.relation;

/** 好友申请创建后的确定性结果。 */
public record FriendRequestResultDto(String requestId, String status, boolean autoAccepted) {}
