package im.phantom.web.account;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegisterResponse(
        @JsonProperty("account_id") String accountId,
        @JsonProperty("phantom_user_id") String phantomUserId,
        @JsonProperty("status") String status) {
}
