package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.joblab.scheduler.ticket.PaymentModels.*;

@Service
public class TicketPaymentService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final RowMapper<Merchandise> MERCH = (rs, row) ->
            new Merchandise(UUID.fromString(rs.getString("id")), rs.getString("label"), rs.getInt("price_fen"));
    private static final RowMapper<Pickup> PICKUP = (rs, row) ->
            new Pickup(UUID.fromString(rs.getString("id")), rs.getString("label"));
    private static final String RECEIPT = """
            SELECT p.id,p.reservation_id,p.merchandise_id,p.pickup_point_id,m.label AS merchandise_label,
                   d.label AS pickup_label,p.ticket_price_fen,p.merch_price_fen,p.paid_at
            FROM concert_payment p
            LEFT JOIN concert_merchandise m ON m.id=p.merchandise_id
            LEFT JOIN concert_pickup_point d ON d.id=p.pickup_point_id
            WHERE p.tenant_id=? AND p.buyer_id=?
            """;
    private static final RowMapper<Receipt> RECEIPT_ROW = (rs, row) -> {
        int ticket = rs.getInt("ticket_price_fen");
        int extra = rs.getInt("merch_price_fen");
        return new Receipt(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("reservation_id")),
                uuid(rs.getString("merchandise_id")), uuid(rs.getString("pickup_point_id")),
                rs.getString("merchandise_label"), rs.getString("pickup_label"), ticket, extra,
                (long) ticket + extra, rs.getTimestamp("paid_at").toInstant());
    };

    private final TicketStore tickets;
    private final JdbcTemplate jdbc;

    TicketPaymentService(TicketStore tickets, JdbcTemplate jdbc) {
        this.tickets = tickets;
        this.jdbc = jdbc;
    }

    public Checkout checkout(TenantActor actor, UUID id) {
        var reservation = owned(actor, id);
        String tenant = actor.tenantId().toString();
        String event = reservation.eventId().toString();
        var merchandise = jdbc.query("""
                SELECT id,label,price_fen FROM concert_merchandise
                WHERE tenant_id=? AND event_id=? ORDER BY price_fen,id
                """, MERCH, tenant, event);
        var pickup = jdbc.query("""
                SELECT id,label FROM concert_pickup_point
                WHERE tenant_id=? AND event_id=? ORDER BY label,id
                """, PICKUP, tenant, event);
        return new Checkout(tickets.now(), reservation, merchandise, pickup,
                receipt(actor, " AND p.reservation_id=?", id.toString()).orElse(null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt pay(TenantActor actor, UUID id, String key, PayRequest selection) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment Idempotency-Key");
        }
        if ((selection.merchandiseId() == null) != (selection.pickupPointId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose both merchandise and pickup point");
        }
        var replay = byKey(actor, key);
        if (replay.isPresent()) return verifyReplay(replay.get(), id, selection);

        var initial = owned(actor, id);
        // Expiry and payment take the same offer lock first.
        tickets.lockOffer(actor.tenantId(), initial.eventId(), initial.offerId()).orElseThrow();
        replay = byKey(actor, key);
        if (replay.isPresent()) return verifyReplay(replay.get(), id, selection);

        var reservation = owned(actor, id);
        if (reservation.state() == TicketModels.ReservationState.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket was already paid");
        }
        if (reservation.state() != TicketModels.ReservationState.LOCKED
                || !reservation.expiresAt().isAfter(tickets.now())) throw expired();

        String tenant = actor.tenantId().toString();
        String event = reservation.eventId().toString();
        int extraPrice = 0;
        if (selection.merchandiseId() != null) {
            var merchandise = jdbc.query("""
                    SELECT id,label,price_fen FROM concert_merchandise
                    WHERE tenant_id=? AND event_id=? AND id=?
                    """, MERCH, tenant, event, selection.merchandiseId().toString());
            List<Pickup> points = jdbc.query("""
                    SELECT id,label FROM concert_pickup_point
                    WHERE tenant_id=? AND event_id=? AND id=?
                    """, PICKUP, tenant, event, selection.pickupPointId().toString());
            if (merchandise.isEmpty() || points.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchandise or pickup point is unavailable for this concert");
            }
            extraPrice = merchandise.get(0).priceFen();
        }

        UUID receiptId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO concert_payment
                        (id,tenant_id,event_id,buyer_id,reservation_id,payment_key,merchandise_id,pickup_point_id,
                         ticket_price_fen,merch_price_fen)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, receiptId.toString(), tenant, event, actor.accountId().toString(), id.toString(), key,
                    string(selection.merchandiseId()), string(selection.pickupPointId()), reservation.priceFen(), extraPrice);
        } catch (DuplicateKeyException ex) {
            return byKey(actor,key).map(row -> verifyReplay(row,id,selection)).orElseThrow(() -> ex);
        }
        int paid = jdbc.update("""
                UPDATE ticket_reservation SET state='PAID'
                WHERE id=? AND tenant_id=? AND buyer_id=? AND state='LOCKED' AND expires_at>CURRENT_TIMESTAMP(6)
                """, id.toString(), tenant, actor.accountId().toString());
        if (paid != 1) throw expired();
        return receipt(actor, " AND p.id=?", receiptId.toString()).orElseThrow();
    }

    private TicketModels.ReservationView owned(TenantActor actor, UUID id) {
        return tickets.reservation(actor, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket reservation not found"));
    }

    private Optional<Receipt> byKey(TenantActor actor, String key) {
        return receipt(actor, " AND p.payment_key=?", key);
    }

    private Optional<Receipt> receipt(TenantActor actor, String condition, String value) {
        return jdbc.query(RECEIPT + condition, RECEIPT_ROW, actor.tenantId().toString(), actor.accountId().toString(), value)
                .stream().findFirst();
    }

    private static Receipt verifyReplay(Receipt receipt, UUID id, PayRequest request) {
        if (!receipt.reservationId().equals(id) || !Objects.equals(receipt.merchandiseId(), request.merchandiseId())
                || !Objects.equals(receipt.pickupPointId(), request.pickupPointId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment key was used for a different request");
        }
        return receipt;
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ticket hold has expired");
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }
}
