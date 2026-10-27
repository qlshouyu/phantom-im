package im.phantom.api.relation;

/** 发起好友申请。 */
public record CreateFriendRequestRequest(String toUserId, String verificationMessage) {}
