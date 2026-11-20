package io.joblab.scheduler.fulfillment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
class ConcertDemandIntake {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ConcertDemandIntake(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelay = 1000)
    void scan() {
        for (int batch = 0; batch < 40 && Boolean.TRUE.equals(transactions.execute(status -> ingest())); batch++) {
            // Commit each payment separately so the same payment can be retried safely.
        }
        for (int batch = 0; batch < 25 && Boolean.TRUE.equals(transactions.execute(status -> plan())); batch++) {
            // Each deficit is replanned from the quantities protected by the demand row lock.
        }
    }

    private boolean ingest() {
        var payments = jdbc.query("""
                SELECT id,tenant_id,event_id,merchandise_id,pickup_point_id
                FROM concert_payment
                WHERE fulfillment_seen_at IS NULL
                ORDER BY paid_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new PaidItem(rs.getString("id"),
                new DemandKey(rs.getString("tenant_id"),rs.getString("event_id"),
                        rs.getString("merchandise_id"),rs.getString("pickup_point_id"))));
        if (payments.isEmpty()) return false;
        var payment = payments.get(0);
        if (payment.demand().merchandise() != null) {
            jdbc.update("""
                    INSERT INTO concert_pickup_demand (tenant_id,event_id,merchandise_id,pickup_point_id,paid_qty)
                    VALUES (?,?,?,?,1)
                    ON DUPLICATE KEY UPDATE paid_qty=paid_qty+1
                    """, payment.demand().parameters());
        }
        jdbc.update("UPDATE concert_payment SET fulfillment_seen_at=CURRENT_TIMESTAMP(6) WHERE id=?",payment.id());
        return true;
    }

    private boolean plan() {
        var deficits = jdbc.query("""
                SELECT tenant_id,event_id,merchandise_id,pickup_point_id,deficit
                FROM concert_pickup_demand WHERE deficit>0
                ORDER BY deficit DESC,tenant_id,event_id,merchandise_id,pickup_point_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new NeedsStock(new DemandKey(rs.getString("tenant_id"),rs.getString("event_id"),
                rs.getString("merchandise_id"),rs.getString("pickup_point_id")),rs.getInt("deficit")));
        if (deficits.isEmpty()) return false;
        var item = deficits.get(0);
        var key = item.demand();
        jdbc.update("""
                INSERT INTO concert_restock_order (id,tenant_id,event_id,merchandise_id,pickup_point_id,quantity,deadline_at)
                SELECT ?,?,?,?,?,?,DATE_SUB(starts_at, INTERVAL 30 MINUTE)
                FROM concert_event WHERE tenant_id=? AND id=?
                """, UUID.randomUUID().toString(),key.tenant(),key.event(),key.merchandise(),key.pickup(),
                item.quantity(),key.tenant(),key.event());
        jdbc.update("""
                UPDATE concert_pickup_demand SET planned_qty=planned_qty+?
                WHERE tenant_id=? AND event_id=? AND merchandise_id=? AND pickup_point_id=?
                """, item.quantity(),key.tenant(),key.event(),key.merchandise(),key.pickup());
        return true;
    }

    private record DemandKey(String tenant, String event, String merchandise, String pickup) {
        Object[] parameters() {
            return new Object[]{tenant,event,merchandise,pickup};
        }
    }
    private record PaidItem(String id, DemandKey demand) { }
    private record NeedsStock(DemandKey demand, int quantity) { }
}
