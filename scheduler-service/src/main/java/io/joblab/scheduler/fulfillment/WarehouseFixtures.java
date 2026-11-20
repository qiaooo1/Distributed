package io.joblab.scheduler.fulfillment;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(20)
@ConditionalOnProperty(name = "joblab.security.demo-accounts", havingValue = "true")
class WarehouseFixtures implements ApplicationRunner {

    private static final String ACME = "00000000-0000-0000-0000-000000000001";
    private static final String A = "ca00e512-2c61-4cee-9920-026a328ec351";
    private static final String B = "ca00e512-2c61-4cee-9920-026a328ec352";
    private final JdbcTemplate jdbc;

    WarehouseFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        warehouse(A,"滨海前置仓",18,false);
        warehouse(B,"星港中转仓",25,true);
        for (String sku : new String[]{"ca00e512-2c61-4cee-9920-026a328ec331","ca00e512-2c61-4cee-9920-026a328ec332"}) {
            jdbc.update("""
                    INSERT INTO concert_warehouse_stock (warehouse_id,merchandise_id,available)
                    VALUES (?,?,18),(?,?,26)
                    ON DUPLICATE KEY UPDATE warehouse_id=warehouse_id
                    """,A,sku,B,sku);
        }
    }

    private void warehouse(String id, String label, int eta, boolean accepting) {
        jdbc.update("""
                INSERT INTO concert_warehouse (id,tenant_id,label,eta_minutes,accepting)
                VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,id,ACME,label,eta,accepting);
    }
}
