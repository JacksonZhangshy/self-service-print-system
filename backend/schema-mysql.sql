-- ============================================================
-- 自助收费打印系统 · MySQL 8.x 建表脚本（v1.1）
-- 生产部署：mysql -uroot -p < schema-mysql.sql
-- 与 JPA 实体一一对应；demo profile 由 Hibernate 自动建表，无需本脚本。
-- ============================================================
CREATE DATABASE IF NOT EXISTS print_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE print_system;

-- 打印用户（手机号即账号）
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    phone         VARCHAR(20) NOT NULL,
    nickname      VARCHAR(50) NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'USER',
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BLOCKED',
    created_at    DATETIME    NULL,
    last_login_at DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '打印用户';

-- 管理员（BCrypt 口令）
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME     NULL,
    last_login_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理员账号';

-- 服务端会话（只存 token 的 SHA-256）
CREATE TABLE IF NOT EXISTS user_session (
    token_hash   VARCHAR(64) NOT NULL,
    subject_type VARCHAR(10) NOT NULL COMMENT 'USER/ADMIN',
    subject_id   BIGINT      NOT NULL,
    display_name VARCHAR(64) NULL,
    created_at   DATETIME    NOT NULL,
    last_seen_at DATETIME    NOT NULL,
    expires_at   DATETIME    NOT NULL,
    client_ip    VARCHAR(45) NULL,
    PRIMARY KEY (token_hash),
    KEY idx_session_subject (subject_type, subject_id),
    KEY idx_session_expire (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '登录会话';

-- 短信验证码（只存散列）
CREATE TABLE IF NOT EXISTS sms_code (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    phone      VARCHAR(20) NOT NULL,
    code_hash  VARCHAR(64) NOT NULL,
    purpose    VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
    expires_at DATETIME    NOT NULL,
    attempts   INT         NOT NULL DEFAULT 0,
    client_ip  VARCHAR(45) NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sms_phone (phone),
    KEY idx_sms_ip (client_ip, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '短信验证码';

-- 上传文件
CREATE TABLE IF NOT EXISTS stored_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_path  VARCHAR(500) NOT NULL COMMENT '原始上传件',
    print_path    VARCHAR(500) NULL COMMENT '实际打印件（Office 为转换后 PDF）',
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    extension     VARCHAR(16)  NULL,
    page_count    INT          NOT NULL DEFAULT 1,
    page_source   VARCHAR(16)  NOT NULL DEFAULT 'REAL' COMMENT 'REAL/ESTIMATE',
    converted     TINYINT(1)   NOT NULL DEFAULT 0,
    purged        TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_file_user (user_id),
    CONSTRAINT fk_file_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '待打印文件';

-- 收费标准（单行）
CREATE TABLE IF NOT EXISTS price_config (
    id              BIGINT        NOT NULL,
    bw_a4           DECIMAL(10,2) NOT NULL DEFAULT 0.30,
    bw_a3           DECIMAL(10,2) NOT NULL DEFAULT 0.60,
    color_a4        DECIMAL(10,2) NOT NULL DEFAULT 1.00,
    color_a3        DECIMAL(10,2) NOT NULL DEFAULT 2.00,
    duplex_discount DECIMAL(5,2)  NOT NULL DEFAULT 0.90 COMMENT '双面系数',
    min_charge      DECIMAL(10,2) NOT NULL DEFAULT 0.30 COMMENT '每单最低收费',
    updated_at      DATETIME      NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '收费标准';

-- 订单
CREATE TABLE IF NOT EXISTS print_order (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(40)   NOT NULL,
    user_id       BIGINT        NOT NULL,
    file_id       BIGINT        NOT NULL,
    terminal_id   VARCHAR(64)   NULL COMMENT '下单终端',
    copies        INT           NOT NULL DEFAULT 1,
    color_mode    VARCHAR(10)   NOT NULL DEFAULT 'BW',
    duplex        VARCHAR(10)   NOT NULL DEFAULT 'SINGLE',
    paper_size    VARCHAR(8)    NOT NULL DEFAULT 'A4',
    page_count    INT           NOT NULL DEFAULT 1,
    amount        DECIMAL(12,2) NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'CREATED'
                  COMMENT 'CREATED/PAYING/PAID/PRINTING/PRINTED/PRINT_FAILED/CANCELLED/EXPIRED/REFUNDING/REFUNDED',
    pay_method    VARCHAR(10)   NULL COMMENT 'ALIPAY/WECHAT',
    pay_mode      VARCHAR(8)    NULL COMMENT 'REAL/MOCK',
    trade_no      VARCHAR(64)   NULL,
    expire_at     DATETIME      NOT NULL COMMENT '支付超时时间',
    created_at    DATETIME      NULL,
    paid_at       DATETIME      NULL,
    cancel_reason VARCHAR(200)  NULL,
    refund_status VARCHAR(16)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PROCESSING/SUCCESS/FAILED',
    refund_amount DECIMAL(12,2) NULL,
    refund_no     VARCHAR(64)   NULL,
    refunded_at   DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_user (user_id),
    KEY idx_order_status (status),
    KEY idx_order_paid_at (paid_at),
    KEY idx_order_expire (status, expire_at),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_order_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '打印订单';

-- 打印任务
CREATE TABLE IF NOT EXISTS print_task (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_id      BIGINT      NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PRINTING/SUCCESS/FAILED/CANCELLED',
    agent_id      VARCHAR(64) NULL,
    terminal_id   VARCHAR(64) NULL COMMENT '目标终端，空=任意终端',
    retry_count   INT         NOT NULL DEFAULT 0,
    actual_pages  INT         NULL COMMENT '打印机回传实际张数',
    fail_reason   VARCHAR(500) NULL,
    created_at    DATETIME    NULL,
    claimed_at    DATETIME    NULL,
    finished_at   DATETIME    NULL,
    deadline_at   DATETIME    NULL COMMENT '领取超时时间，用于回收僵尸任务',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_order (order_id),
    KEY idx_task_status (status),
    KEY idx_task_deadline (status, deadline_at),
    CONSTRAINT fk_task_order FOREIGN KEY (order_id) REFERENCES print_order (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '打印任务';

-- 退款流水
CREATE TABLE IF NOT EXISTS refund_record (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_no          VARCHAR(40)   NOT NULL,
    amount            DECIMAL(12,2) NOT NULL,
    channel           VARCHAR(10)   NOT NULL,
    channel_refund_no VARCHAR(64)   NULL,
    status            VARCHAR(16)   NOT NULL COMMENT 'SUCCESS/PROCESSING/FAILED',
    reason            VARCHAR(200)  NULL,
    operator          VARCHAR(64)   NULL,
    error_msg         VARCHAR(500)  NULL,
    created_at        DATETIME      NULL,
    finished_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_refund_order (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '退款流水';

-- 审计日志
CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    actor      VARCHAR(64)  NULL,
    action     VARCHAR(64)  NOT NULL,
    target     VARCHAR(64)  NULL,
    detail     VARCHAR(1000) NULL,
    ip         VARCHAR(45)  NULL,
    created_at DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_audit_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理端操作审计';
