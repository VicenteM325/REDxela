-- Esquema de REDxela (H2). Idempotente: se ejecuta en cada arranque.

CREATE TABLE IF NOT EXISTS merchandise_type (
    code        VARCHAR(30)  PRIMARY KEY,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    -- Recursos requeridos separados por coma, en el orden natural de adquisición
    resources   VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS product (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku               VARCHAR(20)  NOT NULL UNIQUE,
    name              VARCHAR(100) NOT NULL,
    merchandise_type  VARCHAR(30)  NOT NULL REFERENCES merchandise_type(code)
);

CREATE TABLE IF NOT EXISTS customer (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    service_level INT          NOT NULL CHECK (service_level BETWEEN 1 AND 5),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES customer(id),
    service_level    INT         NOT NULL,
    merchandise_type VARCHAR(30) NOT NULL REFERENCES merchandise_type(code),
    total_units      INT         NOT NULL,
    status           VARCHAR(30) NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES orders(id),
    product_id BIGINT NOT NULL REFERENCES product(id),
    quantity   INT    NOT NULL CHECK (quantity > 0)
);

-- Ubicaciones físicas del almacén que contienen unidades de un producto
CREATE TABLE IF NOT EXISTS stock_location (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product(id),
    aisle      INT    NOT NULL,
    level      INT    NOT NULL,
    position   INT    NOT NULL,
    quantity   INT    NOT NULL CHECK (quantity >= 0)
);
