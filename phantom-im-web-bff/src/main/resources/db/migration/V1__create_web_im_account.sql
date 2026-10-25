CREATE TABLE IF NOT EXISTS web_im_account (
    account_id      CHAR(36)     NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    login_name      VARCHAR(32)  NOT NULL,
    nickname        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    phantom_user_id VARCHAR(64)  NULL,
    status          VARCHAR(24)  NOT NULL,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    PRIMARY KEY (account_id),
    UNIQUE KEY uk_web_im_account_login (app_id, login_name),
    UNIQUE KEY uk_web_im_account_phantom_user (phantom_user_id),
    KEY idx_web_im_account_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
