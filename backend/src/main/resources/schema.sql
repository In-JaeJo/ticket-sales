CREATE TABLE IF NOT EXISTS stock (
    product_id BIGINT PRIMARY KEY,
    quantity   INT    NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    product_id BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    amount     BIGINT      NOT NULL,
    run_id     BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_product (user_id, product_id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id   CHAR(36)    NOT NULL UNIQUE,
    run_id       BIGINT      NOT NULL,
    topic        VARCHAR(64) NOT NULL,
    message_key  VARCHAR(64) NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    INDEX idx_unpublished (published_at, id)
);

-- 취소로 재고를 되돌린 주문. 같은 주문의 재고를 두 번 되돌리지 않게 한다(DB 방식)
CREATE TABLE IF NOT EXISTS stock_restore (
    order_id BIGINT PRIMARY KEY
);

INSERT IGNORE INTO stock (product_id, quantity) VALUES (1, 100);
