package im.phantom.api.relation;

/** 按 Phantom ID 查询得到的资料卡；不包含跨应用资料。 */
public record UserCardDto(
        String userId,
        String nickname,
        String avatarUrl,
        String remark,
        boolean friend,
        boolean blockedByMe
) {}
