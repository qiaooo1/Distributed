CREATE TABLE sale (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sale_date DATE NOT NULL,
    sku VARCHAR(40) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    KEY idx_sale_date (sale_date, id)
) ENGINE=InnoDB;

INSERT INTO sale (sale_date, sku, quantity, unit_price) VALUES
    ('2026-01-01', 'KB-100', 2, 129.90),
    ('2026-01-01', 'MS-200', 3, 59.90),
    ('2026-01-02', 'KB-100', 1, 129.90),
    ('2026-01-02', 'DOCK-300', 2, 219.00),
    ('2026-01-03', 'MS-200', 5, 59.90),
    ('2026-01-03', 'DOCK-300', 1, 219.00);
