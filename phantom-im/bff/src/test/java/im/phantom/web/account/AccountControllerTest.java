package im.phantom.web.account;

import im.phantom.web.auth.SessionSupport;
import im.phantom.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountControllerTest {
    private AccountService accounts;
    private SessionSupport sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        sessions = mock(SessionSupport.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(accounts, sessions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void requiresSessionAndReturnsResolvedAccount() throws Exception {
        when(accounts.lookup("bob_1"))
                .thenReturn(new AccountLookupResponse("u_bob", "bob_1", "Bob"));

        mvc.perform(get("/api/accounts/lookup").param("identifier", "bob_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.phantomUserId").value("u_bob"))
                .andExpect(jsonPath("$.data.loginName").value("bob_1"))
                .andExpect(jsonPath("$.data.nickname").value("Bob"));

        verify(sessions).require(any());
    }
}
