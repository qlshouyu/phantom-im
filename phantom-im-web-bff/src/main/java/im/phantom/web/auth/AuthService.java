package im.phantom.web.auth;

import im.phantom.web.account.Account;
import im.phantom.web.account.AccountService;
import im.phantom.web.account.AccountStatus;
import im.phantom.web.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AccountService accounts;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public AuthService(AccountService accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("invalid-password-placeholder");
    }

    public SessionAccount login(LoginRequest request, HttpServletRequest servletRequest) {
        Account account = accounts.findByLoginName(request.loginName()).orElse(null);
        boolean passwordOk = passwordEncoder.matches(request.password(),
                account == null ? dummyHash : account.passwordHash());
        if (account == null || !passwordOk || !account.status().canLogin()) {
            throw new ApiException(2003, HttpStatus.UNAUTHORIZED, "登录名或密码错误");
        }
        HttpSession existing = servletRequest.getSession(false);
        if (existing != null) servletRequest.changeSessionId();
        HttpSession session = servletRequest.getSession(true);
        long now = System.currentTimeMillis();
        session.setAttribute(SessionKeys.ACCOUNT_ID, account.accountId());
        session.setAttribute(SessionKeys.PHANTOM_USER_ID, account.phantomUserId());
        session.setAttribute(SessionKeys.LOGIN_NAME, account.loginName());
        session.setAttribute(SessionKeys.NICKNAME, account.nickname());
        session.setAttribute(SessionKeys.AUTHENTICATED_AT, now);
        return new SessionAccount(account.accountId(), account.phantomUserId(),
                account.loginName(), account.nickname(), now);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    public MeResponse me(SessionAccount account) {
        if (account.accountId() == null) throw new IllegalStateException("missing account");
        return new MeResponse(account.accountId(), account.phantomUserId(),
                account.loginName(), account.nickname());
    }
}
