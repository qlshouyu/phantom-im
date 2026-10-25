-- Web IM 接入方独立 schema 与最小权限账号。Flyway 在 BFF 启动时创建业务表。
CREATE DATABASE IF NOT EXISTS phantom_web_im
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'phantom_web_im'@'%' IDENTIFIED BY 'phantom_web_im_dev';
ALTER USER 'phantom_web_im'@'%' IDENTIFIED BY 'phantom_web_im_dev';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
    ON phantom_web_im.* TO 'phantom_web_im'@'%';
FLUSH PRIVILEGES;
