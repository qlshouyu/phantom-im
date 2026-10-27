package im.phantom.web.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AccountRepository {
    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getString("account_id"),
            rs.getString("app_id"),
            rs.getString("login_name"),
            rs.getString("nickname"),
            rs.getString("password_hash"),
            rs.getString("phantom_user_id"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        jdbc.update("""
                INSERT INTO web_im_account(account_id, app_id, login_name, nickname, password_hash,
                    phantom_user_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, account.accountId(), account.appId(), account.loginName(), account.nickname(),
                account.passwordHash(), account.phantomUserId(), account.status().name(),
                account.createdAt(), account.updatedAt());
    }

    public Optional<Account> findByLoginName(String appId, String loginName) {
        return jdbc.query("SELECT * FROM web_im_account WHERE app_id=? AND login_name=?",
                MAPPER, appId, loginName).stream().findFirst();
    }

    public Optional<Account> findByPhantomUserId(String appId, String phantomUserId) {
        return jdbc.query("SELECT * FROM web_im_account WHERE app_id=? AND phantom_user_id=?",
                MAPPER, appId, phantomUserId).stream().findFirst();
    }

    public Optional<Account> findById(String accountId) {
        return jdbc.query("SELECT * FROM web_im_account WHERE account_id=?",
                MAPPER, accountId).stream().findFirst();
    }

    public boolean transition(String accountId, AccountStatus expected, AccountStatus target,
                              String phantomUserId) {
        return jdbc.update("""
                UPDATE web_im_account
                   SET status=?, phantom_user_id=?, updated_at=?
                 WHERE account_id=? AND status=?
                """, target.name(), phantomUserId, System.currentTimeMillis(),
                accountId, expected.name()) == 1;
    }
}
