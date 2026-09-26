DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS goods;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role INT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE goods (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_name VARCHAR(200) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    pay_price DECIMAL(10,2) NOT NULL,
    create_ts BIGINT NOT NULL,
    status INT NOT NULL DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_goods_id (goods_id),
    INDEX idx_create_ts (create_ts),
    INDEX idx_status_create_ts (status, create_ts)
);

-- idx_create_ts：系统概览“近 N 分钟下单速率”靠它做 range 扫描，否则每次刷新都全表扫；
-- idx_status_create_ts：已支付速率与 GMV 都是 status + 时间两个谓词。

CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    username VARCHAR(50) NOT NULL,
    role TINYINT DEFAULT NULL,
    action VARCHAR(50) NOT NULL,
    detail VARCHAR(500) DEFAULT '',
    ip VARCHAR(45) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_create_time (create_time)
);

-- 日志列表按 create_time desc + id desc 翻页，无索引时大表会 filesort。
