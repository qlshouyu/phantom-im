package im.phantom.web.account;

import im.phantom.web.auth.SessionSupport;
import im.phantom.web.common.ApiResponse;
import im.phantom.web.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accounts;
    private final SessionSupport sessions;

    public AccountController(AccountService accounts, SessionSupport sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @GetMapping("/lookup")
    public ApiResponse<AccountLookupResponse> lookup(@RequestParam String identifier,
                                                      HttpServletRequest request) {
        sessions.require(request);
        return ApiResponse.ok(accounts.lookup(identifier), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
