package im.phantom.search.app;

import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.GroupEvent;
import im.phantom.proto.v1.ProfileEvent;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.search.app.SearchDtos.ContactHit;
import im.phantom.search.app.SearchDtos.ConversationSearch;
import im.phantom.search.app.SearchDtos.GroupHit;
import im.phantom.search.app.SearchDtos.MessageHit;
import im.phantom.search.app.SearchDtos.SearchResult;
import im.phantom.search.app.SearchDtos.SearchSuggestions;
import im.phantom.search.infra.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 权限内联系人、群组和聊天记录搜索及索引维护。 */
@Service
public class SearchAppService {

    private static final int MAX_LIMIT = 50;
    private final SearchRepository repository;

    public SearchAppService(SearchRepository repository) { this.repository = repository; }

    public SearchResult search(String userId, String query, int limit) {
        String keyword = normalize(query);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIMIT));
        List<ContactHit> contacts = repository.contacts(userId, keyword, pageSize).stream()
                .map(row -> new ContactHit(row.userId(), row.nickname(), row.remark(), row.avatarUrl()))
                .toList();
        List<GroupHit> groups = repository.groups(userId, keyword, pageSize).stream()
                .map(row -> new GroupHit(row.groupId(), row.convId(), row.name(), row.avatarUrl()))
                .toList();
        List<MessageHit> messages = searchMessages(userId, null, keyword, pageSize);
        return new SearchResult(contacts, groups, messages, contacts.size(), groups.size(), messages.size());
    }

    public ConversationSearch searchConversation(String userId, String convId, String query, int limit) {
        if (convId == null || convId.isBlank()) {
            throw new BizException(ErrorCodes.CONV_INVALID, "conv_id 不能为空");
        }
        String keyword = normalize(query);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIMIT));
        List<MessageHit> messages = searchMessages(userId, convId, keyword, pageSize);
        return new ConversationSearch(convId, messages, messages.size());
    }

    public SearchSuggestions suggest(String userId, String query, int limit) {
        String keyword = normalize(query);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 20));
        List<ContactHit> contacts = repository.contacts(userId, keyword, pageSize).stream()
                .map(row -> new ContactHit(row.userId(), row.nickname(), row.remark(), row.avatarUrl()))
                .toList();
        List<GroupHit> groups = repository.groups(userId, keyword, pageSize).stream()
                .map(row -> new GroupHit(row.groupId(), row.convId(), row.name(), row.avatarUrl()))
                .toList();
        return new SearchSuggestions(contacts, groups, contacts.size() + groups.size());
    }

    @Transactional
    public void indexMessage(Msg message) {
        String appId = repository.appIdForUser(message.getSenderId());
        if (appId == null) return;
        long now = message.getServerTime();
        for (String owner : repository.participantIds(message.getConvId())) {
            repository.upsertDocument("msg:" + message.getMsgId() + ":" + owner, appId, "MESSAGE",
                    owner, message.getConvId(), message.getMsgId(), senderTitle(message),
                    searchableText(message), now);
        }
    }

    @Transactional
    public void reindexUser(String userId) {
        String appId = repository.appIdForUser(userId);
        if (appId == null) return;
        long now = System.currentTimeMillis();
        for (var contact : repository.contacts(userId, "", 10_000)) {
            repository.upsertDocument("contact:" + userId + ":" + contact.userId(), appId, "CONTACT",
                    userId, null, null, contact.nickname(), contact.remark(), now);
        }
        for (var group : repository.groups(userId, "", 1_000)) {
            repository.upsertDocument("group:" + userId + ":" + group.groupId(), appId, "GROUP",
                    userId, group.convId(), null, group.name(), "", now);
        }
        for (Msg message : repository.accessibleMessages(userId, null, 2_000)) {
            repository.upsertDocument("msg:" + message.getMsgId() + ":" + userId, appId, "MESSAGE",
                    userId, message.getConvId(), message.getMsgId(), senderTitle(message),
                    searchableText(message), message.getServerTime());
        }
    }

    @Transactional
    public void onSyncEvent(byte[] payload) throws Exception {
        SyncEventEnvelope envelope = SyncEventEnvelope.parseFrom(payload);
        String userId = envelope.getUserId();
        SyncEvent event = envelope.getEvent();
        switch (event.getEventCase()) {
            case NEW_MESSAGE -> {
                if (event.getNewMessage().hasMsg()) {
                    indexMessage(event.getNewMessage().getMsg());
                }
            }
            case RECALL -> repository.message(event.getRecall().getMsgId()).ifPresent(this::indexMessage);
            case RELATION -> applyRelation(userId, event.getRelation());
            case GROUP -> applyGroup(userId, event.getGroup());
            case PROFILE -> applyProfile(userId, event.getProfile());
            // 草稿、送达/已读、会话置顶/免打扰、举报提示等不改变搜索可见内容。
            case DRAFT, READ_POSITION, READ_RECEIPT, CONV_STATE, MSG_STATUS, REPORT,
                    RESTRICTED_DELIVERY, EVENT_NOT_SET -> { }
        }
    }

    /** 显式运维/管理入口；普通消息与同步事件不得调用。 */
    @Transactional
    public void rebuildUser(String userId) {
        reindexUser(userId);
    }

    private void applyRelation(String userId, RelationEvent event) {
        switch (event.getType()) {
            case RELATION_EVENT_TYPE_APPLY_ACCEPTED,
                    RELATION_EVENT_TYPE_FRIEND_SETTING_CHANGED -> upsertContact(userId, event.getPeerId());
            case RELATION_EVENT_TYPE_FRIEND_DELETED,
                    RELATION_EVENT_TYPE_BLACKLIST_ADDED -> repository.deleteContactDocument(
                    userId, event.getPeerId());
            default -> { }
        }
    }

    private void applyGroup(String userId, GroupEvent event) {
        switch (event.getType()) {
            case GROUP_EVENT_TYPE_MEMBER_JOINED, GROUP_EVENT_TYPE_PROFILE_CHANGED,
                    GROUP_EVENT_TYPE_NICKNAME_CHANGED -> upsertGroup(userId, event.getGroupId());
            case GROUP_EVENT_TYPE_MEMBER_LEFT, GROUP_EVENT_TYPE_MEMBER_KICKED,
                    GROUP_EVENT_TYPE_DISSOLVED -> {
                repository.deleteGroupDocument(userId, event.getGroupId());
                String convId = repository.conversationIdForGroup(event.getGroupId());
                if (convId != null && !convId.isBlank()) {
                    repository.deleteConversationDocumentsForOwner(userId, convId);
                }
            }
            default -> { }
        }
    }

    private void applyProfile(String userId, ProfileEvent event) {
        switch (event.getType()) {
            case PROFILE_EVENT_TYPE_FRIEND_REMARK -> upsertContact(userId, event.getTargetId());
            case PROFILE_EVENT_TYPE_MY_PROFILE -> refreshContactDocuments(userId);
            case PROFILE_EVENT_TYPE_MY_SETTINGS, PROFILE_EVENT_TYPE_UNSPECIFIED -> { }
        }
    }

    private void upsertContact(String ownerId, String peerId) {
        String appId = repository.appIdForUser(ownerId);
        if (appId == null || peerId == null || peerId.isBlank()) return;
        repository.contact(ownerId, peerId).ifPresent(contact ->
                repository.upsertDocument("contact:" + ownerId + ":" + peerId, appId, "CONTACT",
                        ownerId, null, null, contact.nickname(), contact.remark(), System.currentTimeMillis()));
    }

    private void upsertGroup(String ownerId, String groupId) {
        String appId = repository.appIdForUser(ownerId);
        if (appId == null || groupId == null || groupId.isBlank()) return;
        repository.group(ownerId, groupId).ifPresent(group ->
                repository.upsertDocument("group:" + ownerId + ":" + groupId, appId, "GROUP",
                        ownerId, group.convId(), null, group.name(), "", System.currentTimeMillis()));
    }

    private void refreshContactDocuments(String userId) {
        String appId = repository.appIdForUser(userId);
        if (appId == null) return;
        long now = System.currentTimeMillis();
        for (var contact : repository.contacts(userId, "", 10_000)) {
            repository.upsertDocument("contact:" + userId + ":" + contact.userId(), appId, "CONTACT",
                    userId, null, null, contact.nickname(), contact.remark(), now);
        }
    }

    private List<MessageHit> searchMessages(String userId, String convId, String keyword, int limit) {
        long now = System.currentTimeMillis();
        List<MessageHit> result = new ArrayList<>();
        for (Msg message : repository.accessibleMessages(userId, convId, 2_000)) {
            boolean burned = message.getBurnAfterReadTtlSeconds() > 0
                    && now > message.getServerTime() + message.getBurnAfterReadTtlSeconds() * 1000L;
            String content = message.getRecalled() ? "[撤回]" : burned ? "[已焚毁]"
                    : MessageTextFormat.searchContent(message);
            if (!content.toLowerCase().contains(keyword.toLowerCase())
                    && !(message.getRecalled() && keyword.contains("撤回"))
                    && !(burned && keyword.contains("焚毁"))) {
                continue;
            }
            String digest = message.getRecalled() ? "消息已撤回"
                    : burned ? "媒体已焚毁" : MessageTextFormat.digest(message);
            result.add(new MessageHit(message.getMsgId(), message.getConvId(),
                    repository.conversationTitle(userId, message.getConvId()), message.getSenderId(),
                    digest, message.getConvSeq(), message.getServerTime(), message.getRecalled(), burned));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private String normalize(String query) {
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "搜索关键词不能为空");
        }
        String value = query.strip();
        if (value.codePointCount(0, value.length()) > 100) {
            throw new BizException(ErrorCodes.INVALID_PARAM, "搜索关键词过长");
        }
        return value;
    }

    private String searchableText(Msg message) {
        return message.getRecalled() ? "[撤回]" : MessageTextFormat.searchContent(message);
    }

    private String senderTitle(Msg message) { return message.getSenderId(); }
}
