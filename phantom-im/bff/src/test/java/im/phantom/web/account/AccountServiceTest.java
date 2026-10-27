package im.phantom.web.account;

import im.phantom.web.common.ApiException;
import im.phantom.web.config.WebImProperties;
import im.phantom.web.phantom.PhantomOpenApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {
    private AccountRepository repository;
    private PhantomOpenApiClient openApi;
    private PasswordEncoder encoder;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        openApi = mock(PhantomOpenApiClient.class);
        encoder = mock(PasswordEncoder.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(encoder.encode("password1")).thenReturn("argon2-hash");
        when(repository.findByLoginName(any(), any())).thenReturn(Optional.empty());
        when(repository.transition(any(), eq(AccountStatus.PROVISIONING), eq(AccountStatus.ACTIVE), any()))
                .thenReturn(true);
        WebImProperties properties = new WebImProperties("app_test", "openapi-secret",
                "http://localhost:28080",
                "ws://localhost:28080/ws", "/api/phantom", Duration.ofMinutes(15),
                true, false, List.of("http://localhost:5173"));
        service = new AccountService(repository, openApi, encoder, properties, transaction);
    }

    @Test
    void provisionsStableAccountIdAndReturnsActive() {
        when(openApi.importUser(any(), eq("Alice"))).thenReturn("user-1");
        RegisterResponse response = service.register(new RegistrationRequest(
                "Alice", " Alice_1 ", "password1", "password1"));
        assertThat(response.phantomUserId()).isEqualTo("user-1");
        assertThat(response.status()).isEqualTo("ACTIVE");
        ArgumentCaptor<Account> inserted = ArgumentCaptor.forClass(Account.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().loginName()).isEqualTo("alice_1");
        assertThat(inserted.getValue().passwordHash()).isEqualTo("argon2-hash");
    }

    @Test
    void provisioningFailureMarksAccountRetryable() {
        when(openApi.importUser(any(), any())).thenThrow(new RuntimeException("upstream down"));
        assertThatThrownBy(() -> service.register(new RegistrationRequest(
                "Alice", "alice_1", "password1", "password1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重试");
        verify(repository).transition(any(), eq(AccountStatus.PROVISIONING),
                eq(AccountStatus.PROVISIONING_FAILED), eq(null));
    }

    @Test
    void upstreamApiFailureMarksAccountRetryableAndPreservesMessage() {
        when(openApi.importUser(any(), any())).thenThrow(
                new ApiException(9101, HttpStatus.BAD_GATEWAY,
                        "Phantom 用户开通失败：OpenAPI 签名校验失败"));

        assertThatThrownBy(() -> service.register(new RegistrationRequest(
                "Alice", "alice_1", "password1", "password1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OpenAPI 签名校验失败");

        verify(repository).transition(any(), eq(AccountStatus.PROVISIONING),
                eq(AccountStatus.PROVISIONING_FAILED), eq(null));
    }

    @Test
    void onlyActiveAccountsCanLogin() {
        assertThat(AccountStatus.ACTIVE.canLogin()).isTrue();
        assertThat(AccountStatus.PROVISIONING.canLogin()).isFalse();
        assertThat(AccountStatus.PROVISIONING_FAILED.canLogin()).isFalse();
        assertThat(AccountStatus.DISABLED.canLogin()).isFalse();
    }

    @Test
    void lookupResolvesNormalizedLoginName() {
        Account account = activeAccount("bob_1", "u_bob");
        when(repository.findByLoginName("app_test", "bob_1")).thenReturn(Optional.of(account));

        AccountLookupResponse response = service.lookup(" Bob_1 ");

        assertThat(response).isEqualTo(new AccountLookupResponse("u_bob", "bob_1", "Bob"));
    }

    @Test
    void lookupFallsBackToPhantomUserId() {
        when(repository.findByLoginName("app_test", "u_bob")).thenReturn(Optional.empty());
        when(repository.findByPhantomUserId("app_test", "u_bob"))
                .thenReturn(Optional.of(activeAccount("bob_1", "u_bob")));

        AccountLookupResponse response = service.lookup("u_bob");

        assertThat(response.phantomUserId()).isEqualTo("u_bob");
        assertThat(response.loginName()).isEqualTo("bob_1");
    }

    @Test
    void lookupOnlyReturnsActiveUsers() {
        Account account = new Account("account-1", "app_test", "bob_1", "Bob", "hash",
                "u_bob", AccountStatus.DISABLED, 1, 1);
        when(repository.findByLoginName("app_test", "bob_1")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.lookup("bob_1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未找到该用户");
    }

    private static Account activeAccount(String loginName, String phantomUserId) {
        return new Account("account-1", "app_test", loginName, "Bob", "hash",
                phantomUserId, AccountStatus.ACTIVE, 1, 1);
    }
}
