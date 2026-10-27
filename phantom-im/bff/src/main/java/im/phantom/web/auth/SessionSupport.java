package im.phantom.web.auth;

import im.phantom.web.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SessionSupport {
    public SessionAccount require(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) throw unauthorized();
            Object accountId = session.getAttribute(SessionKeys.ACCOUNT_ID);
            Object phantomUserId = session.getAttribute(SessionKeys.PHANTOM_USER_ID);
            Object loginName = session.getAttribute(SessionKeys.LOGIN_NAME);
            Object nickname = session.getAttribute(SessionKeys.NICKNAME);
            if (accountId == null || phantomUserId == null || loginName == null) throw unauthorized();
            Object authenticatedAt = session.getAttribute(SessionKeys.AUTHENTICATED_AT);
            return new SessionAccount(accountId.toString(), phantomUserId.toString(), loginName.toString(),
                    nickname == null ? "" : nickname.toString(),
                    authenticatedAt instanceof Number number ? number.longValue() : 0L);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException(9002, HttpStatus.SERVICE_UNAVAILABLE, "会话存储暂不可用");
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(2003, HttpStatus.UNAUTHORIZED, "未登录或会话已失效");
    }
}
