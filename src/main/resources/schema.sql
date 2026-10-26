-- Esquema de REDxela (H2). Idempotente: se ejecuta en cada arranque.
-- Persisten el catálogo y las cuentas de clientes. Los pedidos, las existencias y el almacén viven en
-- memoria (son estado de la simulación en curso) y se reinician con la aplicación.

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
