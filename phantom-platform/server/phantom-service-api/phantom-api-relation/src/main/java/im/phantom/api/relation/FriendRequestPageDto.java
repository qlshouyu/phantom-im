package im.phantom.api.relation;

import java.util.List;

/** 好友申请列表与未读角标。 */
public record FriendRequestPageDto(List<FriendRequestDto> items, int unreadCount) {
    public FriendRequestPageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
