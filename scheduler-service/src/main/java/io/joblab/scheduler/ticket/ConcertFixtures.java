package io.joblab.scheduler.ticket;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(10)
@ConditionalOnProperty(name = "joblab.security.demo-accounts", havingValue = "true")
class ConcertFixtures implements ApplicationRunner {

    static final String DEMO_TENANT = "00000000-0000-0000-0000-000000000001";
    private static final String NEON = "ca00e512-2c61-4cee-9920-026a328ec301";
    private static final String ORBIT = "ca00e512-2c61-4cee-9920-026a328ec302";
    private final JdbcTemplate jdbc;

    ConcertFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        event(NEON, "霓虹余响 · 城市回声演唱会", "滨海演艺中心 · 主馆",
                "本场面向演示观众放出早鸟票、内场票和看台票。", -1, 90, 120);
        event(ORBIT, "光轨计划 · 夏夜专场", "星港音乐公园 · 户外舞台",
                "以开售时间为准。", 7, 105, 135);
        offer("ca00e512-2c61-4cee-9920-026a328ec311", NEON, "早鸟票", 19900, 15);
        offer("ca00e512-2c61-4cee-9920-026a328ec312", NEON, "看台票", 36900, 45);
        offer("ca00e512-2c61-4cee-9920-026a328ec313", NEON, "内场票", 59900, 35);
        offer("ca00e512-2c61-4cee-9920-026a328ec321", ORBIT, "看台票", 29900, 40);
        jdbc.update("""
                INSERT INTO concert_merchandise (id,tenant_id,event_id,sku,label,price_fen)
                VALUES (?,?,?,'NEON-SCARF','城市回声限定围巾',6900),
                       (?,?,?,'NEON-PIN','城市回声徽章',2400) ON DUPLICATE KEY UPDATE id=id
                """, "ca00e512-2c61-4cee-9920-026a328ec331", DEMO_TENANT, NEON,
                "ca00e512-2c61-4cee-9920-026a328ec332", DEMO_TENANT, NEON);
        jdbc.update("""
                INSERT INTO concert_pickup_point (id,tenant_id,event_id,label)
                VALUES (?,?,?,'东门周边取货点'),(?,?,?,'西门周边取货点') ON DUPLICATE KEY UPDATE id=id
                """, "ca00e512-2c61-4cee-9920-026a328ec341", DEMO_TENANT, NEON,
                "ca00e512-2c61-4cee-9920-026a328ec342", DEMO_TENANT, NEON);
    }

    private void event(String id, String title, String venue, String description, int saleFrom, int saleTo, int starts) {
        jdbc.update("""
                INSERT INTO concert_event (id, tenant_id, title, venue, description, sale_starts_at, sale_ends_at, starts_at)
                VALUES (?, ?, ?, ?, ?,
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? DAY),
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? DAY),
                        DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? DAY))
                ON DUPLICATE KEY UPDATE id = id
                """, id, DEMO_TENANT, title, venue, description, saleFrom, saleTo, starts);
    }

    private void offer(String id, String event, String label, int price, int capacity) {
        jdbc.update("""
                INSERT INTO concert_offer (id, tenant_id, event_id, label, price_fen, capacity, available)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, id, DEMO_TENANT, event, label, price, capacity, capacity);
    }
}
