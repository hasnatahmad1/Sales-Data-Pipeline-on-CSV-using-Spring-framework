-- Clean, de-duplicated sales rows (sale_id is the idempotency key)
CREATE TABLE IF NOT EXISTS sales (
    sale_id        VARCHAR(64)    PRIMARY KEY,
    sale_date      DATE           NOT NULL,
    product_name   VARCHAR(200)   NOT NULL,
    category       VARCHAR(100)   NOT NULL,
    quantity       INT            NOT NULL,
    unit_price     DECIMAL(12, 2) NOT NULL,
    amount         DECIMAL(14, 2) NOT NULL,
    payment_method VARCHAR(50)    NOT NULL,
    source_file    VARCHAR(255)   NOT NULL,
    loaded_at      TIMESTAMP      NOT NULL
);

-- Files already loaded, by content hash, so re-dropping the same file is a no-op
CREATE TABLE IF NOT EXISTS processed_files (
    file_hash    VARCHAR(64)  PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS pipeline_runs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at      TIMESTAMP   NOT NULL,
    finished_at     TIMESTAMP,
    status          VARCHAR(20) NOT NULL,
    files_processed INT         NOT NULL DEFAULT 0,
    rows_loaded     INT         NOT NULL DEFAULT 0,
    rows_rejected   INT         NOT NULL DEFAULT 0,
    message         VARCHAR(2000)
);

-- Reporting tables, rebuilt from "sales" after every run
CREATE TABLE IF NOT EXISTS report_daily_sales (
    sale_date    DATE PRIMARY KEY,
    total_sales  DECIMAL(14, 2) NOT NULL,
    transactions INT            NOT NULL,
    units_sold   INT            NOT NULL
);

CREATE TABLE IF NOT EXISTS report_top_products (
    product_name VARCHAR(200) PRIMARY KEY,
    category     VARCHAR(100)   NOT NULL,
    units_sold   INT            NOT NULL,
    revenue      DECIMAL(14, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS report_sales_by_category (
    category     VARCHAR(100) PRIMARY KEY,
    total_sales  DECIMAL(14, 2) NOT NULL,
    transactions INT            NOT NULL,
    units_sold   INT            NOT NULL
);

CREATE TABLE IF NOT EXISTS report_sales_by_payment_method (
    payment_method VARCHAR(50) PRIMARY KEY,
    total_sales    DECIMAL(14, 2) NOT NULL,
    transactions   INT            NOT NULL
);
