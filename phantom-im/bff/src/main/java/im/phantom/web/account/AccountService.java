package im.phantom.web.account;

import im.phantom.web.common.ApiException;
import im.phantom.web.config.WebImProperties;
import im.phantom.web.phantom.PhantomOpenApiClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private final AccountRepository repository;
    private final PhantomOpenApiClient openApiClient;
    private final PasswordEncoder passwordEncoder;
    private final WebImProperties properties;
    private final TransactionTemplate transactionTemplate;

    public AccountService(AccountRepository repository, PhantomOpenApiClient openApiClient,
                          PasswordEncoder passwordEncoder, WebImProperties properties,
                          TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.openApiClient = openApiClient;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public RegisterResponse register(RegistrationRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw validation("password_confirm", "确认密码与密码不一致");
        }
        String loginName = normalizeLoginName(request.loginName());
        String nickname = request.nickname().trim();
        Optional<Account> existing = repository.findByLoginName(properties.appId(), loginName);
        if (existing.isPresent()) {
            Account account = existing.get();
            if (account.status() == AccountStatus.PROVISIONING_FAILED
                    && account.nickname().equals(nickname)
                    && passwordEncoder.matches(request.password(), account.passwordHash())) {
                return provision(account);
            }
            throw new ApiException(1002, HttpStatus.CONFLICT, "登录名已被占用");
        }

        long now = System.currentTimeMillis();
        Account account = new Account(UUID.randomUUID().toString(), properties.appId(), loginName,
                nickname, passwordEncoder.encode(request.password()), null,
                AccountStatus.PROVISIONING, now, now);
        try {
            transactionTemplate.executeWithoutResult(status -> repository.insert(account));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(1002, HttpStatus.CONFLICT, "登录名已被占用");
        }
        return provision(account);
    }

    public Optional<Account> findByLoginName(String loginName) {
        return repository.findByLoginName(properties.appId(), normalizeLoginName(loginName));
    }

    public AccountLookupResponse lookup(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.isEmpty()) {
            throw validation("identifier", "请输入登录名或 Phantom ID");
        }
        Optional<Account> account = repository.findByLoginName(properties.appId(),
                normalizeLoginName(value));
        if (account.isEmpty()) {
            account = repository.findByPhantomUserId(properties.appId(), value);
        }
        return account
                .filter(item -> item.status() == AccountStatus.ACTIVE
                        && item.phantomUserId() != null && !item.phantomUserId().isBlank())
                .map(item -> new AccountLookupResponse(item.phantomUserId(), item.loginName(),
                        item.nickname()))
                .orElseThrow(() -> new ApiException(1004, HttpStatus.NOT_FOUND,
                        "未找到该用户，请检查登录名或 Phantom ID"));
    }

    public Optional<Account> findById(String accountId) {
        return repository.findById(accountId);
    }

    private RegisterResponse provision(Account account) {
        try {
            String phantomUserId = openApiClient.importUser(account.accountId(), account.nickname());
            if (!repository.transition(account.accountId(), account.status(), AccountStatus.ACTIVE, phantomUserId)) {
                throw new IllegalStateException("账号状态已变化，无法完成开通");
            }
            return new RegisterResponse(account.accountId(), phantomUserId, AccountStatus.ACTIVE.name());
        } catch (RuntimeException ex) {
            log.warn("账号 Phantom 开通失败 accountId={}", account.accountId(), ex);
            if (account.status() != AccountStatus.PROVISIONING_FAILED) {
                repository.transition(account.accountId(), account.status(),
                        AccountStatus.PROVISIONING_FAILED, null);
            }
            if (ex instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(9101, HttpStatus.BAD_GATEWAY,
                    "Phantom 用户开通失败，请稍后使用相同信息重试");
        }
    }

    public static String normalizeLoginName(String loginName) {
        return loginName == null ? "" : loginName.trim().toLowerCase();
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(1001, HttpStatus.BAD_REQUEST, message + "（字段：" + field + "）");
    }
}
