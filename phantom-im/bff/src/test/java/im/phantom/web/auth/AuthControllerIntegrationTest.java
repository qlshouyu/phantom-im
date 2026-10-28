package im.phantom.web.auth;

import im.phantom.web.account.AccountService;
import im.phantom.web.account.RegisterResponse;
import im.phantom.web.common.GlobalExceptionHandler;
import im.phantom.web.config.WebImProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIntegrationTest {
    private AccountService accountService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        AuthService authService = mock(AuthService.class);
        SessionSupport sessions = mock(SessionSupport.class);
        WebImProperties properties = new WebImProperties("app_test",
                "test-app-secret-at-least-32-bytes-long", "http://localhost:28080",
                "ws://localhost:28080/ws", "/api/phantom", Duration.ofMinutes(15),
                Duration.ofDays(30), true, false, List.of());
        AuthController controller = new AuthController(accountService, authService,
                sessions, new JwtService(properties), properties);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerValidatesFieldsThroughMvcLayer() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"","loginName":"bad-name","password":"short",
                                 "passwordConfirm":"different"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.data.login_name").exists())
                .andExpect(jsonPath("$.data.password").exists());
    }

    @Test
    void successfulRegistrationDoesNotCreateSessionCookie() throws Exception {
        when(accountService.register(any())).thenReturn(
                new RegisterResponse("account-1", "user-1", "ACTIVE"));
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Alice","loginName":"alice_1","password":"password1",
                                 "passwordConfirm":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.phantom_user_id").value("user-1"));
    }
}
