package im.phantom.web.auth;

import im.phantom.web.account.AccountService;
import im.phantom.web.account.RegisterResponse;
import im.phantom.web.account.RegistrationRequest;
import im.phantom.web.common.ApiResponse;
import im.phantom.web.common.ApiException;
import im.phantom.web.common.RequestIdFilter;
import im.phantom.web.config.WebImProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accountService;
    private final AuthService authService;
    private final SessionSupport sessions;
    private final JwtService jwtService;
    private final WebImProperties properties;

    public AuthController(AccountService accountService, AuthService authService,
                          SessionSupport sessions, JwtService jwtService, WebImProperties properties) {
        this.accountService = accountService;
        this.authService = authService;
        this.sessions = sessions;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegistrationRequest request,
                                                   HttpServletRequest servletRequest) {
        return ApiResponse.ok(accountService.register(request), requestId(servletRequest));
    }

    @PostMapping("/login")
    public ApiResponse<MeResponse> login(@Valid @RequestBody LoginRequest request,
                                         HttpServletRequest servletRequest) {
        SessionAccount account = authService.login(request, servletRequest);
        return ApiResponse.ok(authService.me(account), requestId(servletRequest));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResponse.ok(null, requestId(request));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        return ApiResponse.ok(authService.me(sessions.require(request)), requestId(request));
    }

    @PostMapping("/phantom-token")
    public ApiResponse<PhantomTokenResponse> phantomToken(HttpServletRequest request) {
        return ApiResponse.ok(jwtService.issue(sessions.require(request)), requestId(request));
    }

    @PostMapping("/phantom-token/test-expired")
    public ApiResponse<PhantomTokenResponse> expiredToken(HttpServletRequest request) {
        if (!properties.testMode()) {
            throw new ApiException(1001, HttpStatus.NOT_FOUND, "测试模式未启用");
        }
        return ApiResponse.ok(jwtService.issueExpired(sessions.require(request)), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
