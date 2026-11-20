package io.joblab.scheduler.fulfillment;

import io.joblab.messaging.ConcertDispatchProtocol.Lease;
import io.joblab.messaging.ConcertDispatchProtocol.WarehouseReceipt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConcertFulfillmentService {

    private static final RowMapper<Order> ORDER = (rs, i) -> new Order(rs.getString("id"),
            rs.getString("tenant_id"), rs.getString("merchandise_id"), rs.getInt("quantity"),
            rs.getTimestamp("deadline_at").toInstant());
    private static final String ORDER_SUMMARY = """
            SELECT r.id,r.state,r.quantity,r.deadline_at,r.created_at,r.note,
                   e.title AS concert_title,m.label AS merchandise_label,d.label AS pickup_label,w.label AS warehouse_label
            FROM concert_restock_order r
            JOIN concert_event e ON e.id=r.event_id
            JOIN concert_merchandise m ON m.id=r.merchandise_id
            JOIN concert_pickup_point d ON d.id=r.pickup_point_id
            LEFT JOIN concert_warehouse w ON w.id=r.accepted_warehouse_id
            WHERE r.tenant_id=?
            """;
    private static final RowMapper<OrderSummary> SUMMARY = (rs, i) -> new OrderSummary(
            UUID.fromString(rs.getString("id")),rs.getString("concert_title"),rs.getString("merchandise_label"),
            rs.getString("pickup_label"),rs.getString("warehouse_label"),rs.getInt("quantity"),
            rs.getString("state"),rs.getString("note"),rs.getTimestamp("deadline_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    ConcertFulfillmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderSummary> list(UUID tenant) {
        return jdbc.query(ORDER_SUMMARY + " ORDER BY r.created_at DESC,r.id DESC LIMIT 60",SUMMARY,tenant.toString());
    }

    public OrderDetails details(UUID tenant, UUID id) {
        var order = jdbc.query(ORDER_SUMMARY + " AND r.id=?",SUMMARY,tenant.toString(),id.toString())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Restock order not found"));
        var attempts = jdbc.query("""
                SELECT a.id,w.label,a.state,x.reason,a.created_at
                FROM concert_restock_attempt a JOIN concert_warehouse w ON w.id=a.warehouse_id
                LEFT JOIN concert_warehouse_receipt x ON x.attempt_id=a.id
                WHERE a.order_id=? ORDER BY a.created_at,a.id
                """, (rs,n) -> new AttemptSummary(UUID.fromString(rs.getString("id")),rs.getString("label"),
                rs.getString("state"),rs.getString("reason"),rs.getTimestamp("created_at").toInstant()),id.toString());
        return new OrderDetails(order,attempts);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Lease> claim() {
        var due = jdbc.query("""
                SELECT id,tenant_id,merchandise_id,quantity,deadline_at FROM concert_restock_order
                WHERE state='PENDING' OR (state='DISPATCHING' AND lease_until<=CURRENT_TIMESTAMP(6))
                ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, ORDER);
        if (due.isEmpty()) return Optional.empty();
        var order = due.get(0);
        // An ambiguous request keeps its persisted attempt identity across leases.
        var pending = jdbc.queryForList("""
                SELECT id FROM concert_restock_attempt WHERE order_id=? AND state='REQUESTED'
                ORDER BY created_at,id LIMIT 1
                """,String.class,order.id());
        String attempt;
        if (pending.isEmpty()) {
            var candidate = candidate(order);
            if (candidate.isEmpty()) {
                finish(order.id(),"MANUAL",null,"NO_ELIGIBLE_WAREHOUSE");
                return Optional.empty();
            }
            attempt = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO concert_restock_attempt (id,order_id,warehouse_id) VALUES (?,?,?)",
                    attempt,order.id(),candidate.get());
        } else {
            attempt = pending.get(0);
        }
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE concert_restock_order SET state='DISPATCHING',lease_token=?,
                        lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?
                """,token.toString(),order.id());
        return Optional.of(new Lease(UUID.fromString(order.id()),UUID.fromString(attempt),token));
    }

    public Optional<WarehouseReceipt> receipt(UUID attempt) {
        return jdbc.query("""
                SELECT attempt_id,accepted,reason FROM concert_warehouse_receipt WHERE attempt_id=?
                """,(rs,n) -> new WarehouseReceipt(UUID.fromString(rs.getString("attempt_id")),rs.getBoolean("accepted"),
                rs.getString("reason")),attempt.toString()).stream().findFirst();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WarehouseReceipt submitToWarehouse(Lease lease) {
        var order = lockedOrder(lease);
        String warehouse = warehouseFor(order,lease.attemptId());
        var previous = receipt(lease.attemptId());
        if (previous.isPresent()) return previous.get();

        var policy = jdbc.queryForObject("""
                SELECT accepting,TIMESTAMPADD(MINUTE,eta_minutes,CURRENT_TIMESTAMP(6)) <= ? AS on_time
                FROM concert_warehouse WHERE tenant_id=? AND id=?
                """, (rs,n) -> new WarehousePolicy(rs.getBoolean("accepting"),rs.getBoolean("on_time")),
                Timestamp.from(order.deadline()),order.tenant(),warehouse);
        if (policy == null) throw new IllegalStateException("Warehouse is missing");

        String reason;
        if (!policy.onTime()) {
            reason = "DEADLINE_MISSED";
        } else if (!policy.accepting()) {
            reason = "WAREHOUSE_REJECTED";
        } else {
            int deducted = jdbc.update("""
                    UPDATE concert_warehouse_stock SET available=available-?
                    WHERE warehouse_id=? AND merchandise_id=? AND available>=?
                    """,order.quantity(),warehouse,order.merchandise(),order.quantity());
            reason = deducted == 1 ? "ACCEPTED" : "INSUFFICIENT_STOCK";
        }
        boolean accepted = "ACCEPTED".equals(reason);
        jdbc.update("INSERT INTO concert_warehouse_receipt (attempt_id,accepted,reason) VALUES (?,?,?)",
                lease.attemptId().toString(),accepted,reason);
        return new WarehouseReceipt(lease.attemptId(),accepted,reason);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(Lease lease) {
        var order = lockedOrder(lease);
        String warehouse = warehouseFor(order,lease.attemptId());
        var response = receipt(lease.attemptId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Warehouse receipt is missing"));
        jdbc.update("UPDATE concert_restock_attempt SET state=? WHERE id=?",
                response.accepted()?"ACCEPTED":"REJECTED",lease.attemptId().toString());
        finish(order.id(), response.accepted()?"ACCEPTED":"PENDING",response.accepted()?warehouse:null,response.reason());
    }

    private Optional<String> candidate(Order order) {
        return jdbc.queryForList("""
                SELECT w.id FROM concert_warehouse w JOIN concert_warehouse_stock s ON s.warehouse_id=w.id
                WHERE w.tenant_id=? AND s.merchandise_id=? AND s.available>=?
                    AND TIMESTAMPADD(MINUTE,w.eta_minutes,CURRENT_TIMESTAMP(6))<=?
                    AND NOT EXISTS (SELECT 1 FROM concert_restock_attempt a WHERE a.order_id=? AND a.warehouse_id=w.id)
                ORDER BY w.eta_minutes,w.id LIMIT 1
                """,String.class,order.tenant(),order.merchandise(),order.quantity(),Timestamp.from(order.deadline()),order.id())
                .stream().findFirst();
    }

    private Order lockedOrder(Lease lease) {
        if (lease.orderId() == null || lease.attemptId() == null || lease.token() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing dispatch lease fields");
        }
        return jdbc.query("""
                SELECT id,tenant_id,merchandise_id,quantity,deadline_at FROM concert_restock_order
                WHERE id=? AND state='DISPATCHING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
                FOR UPDATE
                """,ORDER,lease.orderId().toString(),lease.token().toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Dispatch lease is no longer active"));
    }

    private String warehouseFor(Order order, UUID attempt) {
        return jdbc.queryForList("""
                SELECT warehouse_id FROM concert_restock_attempt WHERE id=? AND order_id=? AND state='REQUESTED'
                """,String.class,attempt.toString(),order.id()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Dispatch attempt is not active"));
    }

    private void finish(String id, String state, String warehouse, String note) {
        jdbc.update("""
                UPDATE concert_restock_order SET state=?,accepted_warehouse_id=?,note=?,lease_token=NULL,lease_until=NULL
                WHERE id=?
                """,state,warehouse,note,id);
    }

    private record Order(String id, String tenant, String merchandise, int quantity, Instant deadline) { }
    private record WarehousePolicy(boolean accepting, boolean onTime) { }

    public record OrderSummary(UUID id, String concertTitle, String merchandiseLabel, String pickupLabel,
                               String warehouseLabel, int quantity, String state, String note,
                               Instant deadlineAt, Instant createdAt) { }

    public record AttemptSummary(UUID id, String warehouseLabel, String state, String reason, Instant createdAt) { }

    public record OrderDetails(OrderSummary order, List<AttemptSummary> attempts) { }
}
