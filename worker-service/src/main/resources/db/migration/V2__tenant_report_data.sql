ALTER TABLE sale ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE sale SET tenant_id = '00000000-0000-0000-0000-000000000001' WHERE tenant_id IS NULL;
ALTER TABLE sale
    MODIFY COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    DROP INDEX idx_sale_date,
    ADD KEY idx_sale_tenant_date (tenant_id, sale_date, id);

INSERT INTO sale (tenant_id, sale_date, sku, quantity, unit_price) VALUES
    ('00000000-0000-0000-0000-000000000002', '2026-01-01', 'BX-500', 2, 150.00),
    ('00000000-0000-0000-0000-000000000002', '2026-01-02', 'BUT-600', 1, 45.00);
