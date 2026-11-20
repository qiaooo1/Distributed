package io.joblab.scheduler.ticket;

import io.joblab.scheduler.security.TenantActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.joblab.scheduler.ticket.TicketModels.*;

@Repository
class TicketStore {

    private static final RowMapper<Event> EVENT_ROW = (rs, row) -> new Event(
            uuid(rs, "id"), rs.getString("title"), rs.getString("venue"), rs.getString("description"),
            instant(rs, "starts_at"), instant(rs, "sale_starts_at"), instant(rs, "sale_ends_at"));

    private static final RowMapper<OfferView> OFFER_ROW = (rs, row) -> new OfferView(
            uuid(rs, "id"), rs.getString("label"), rs.getInt("price_fen"), rs.getInt("available"));

    private static final RowMapper<ReservationView> RESERVATION_ROW = (rs, row) -> new ReservationView(
            uuid(rs, "id"), uuid(rs, "event_id"), uuid(rs, "offer_id"), rs.getString("title"),
            rs.getString("label"), rs.getInt("price_fen"), ReservationState.valueOf(rs.getString("state")),
            instant(rs, "expires_at"), instant(rs, "created_at"));

    private static final String RESERVATION_VIEW = """
            SELECT r.id, r.event_id, r.offer_id, e.title, o.label, r.price_fen, r.state, r.expires_at, r.created_at
            FROM ticket_reservation r
            JOIN concert_event e ON e.tenant_id = r.tenant_id AND e.id = r.event_id
            JOIN concert_offer o ON o.tenant_id = r.tenant_id AND o.event_id = r.event_id AND o.id = r.offer_id
            WHERE r.tenant_id = ? AND r.buyer_id = ?
            """;

    private final JdbcTemplate jdbc;

    TicketStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    List<Event> events(UUID tenant) {
        return jdbc.query("""
                SELECT id, title, venue, description, starts_at, sale_starts_at, sale_ends_at
                FROM concert_event WHERE tenant_id = ? ORDER BY starts_at, id LIMIT 40
                """, EVENT_ROW, tenant.toString());
    }

    Optional<Event> event(UUID tenant, UUID event) {
        return jdbc.query("""
                SELECT id, title, venue, description, starts_at, sale_starts_at, sale_ends_at
                FROM concert_event WHERE tenant_id = ? AND id = ?
                """, EVENT_ROW, tenant.toString(), event.toString()).stream().findFirst();
    }

    List<OfferView> offers(UUID tenant, UUID event) {
        return jdbc.query("""
                SELECT id, label, price_fen, available FROM concert_offer
                WHERE tenant_id = ? AND event_id = ? ORDER BY price_fen, id
                """, OFFER_ROW, tenant.toString(), event.toString());
    }

    Optional<OfferView> lockOffer(UUID tenant, UUID event, UUID offer) {
        return jdbc.query("""
                SELECT id, label, price_fen, available FROM concert_offer
                WHERE tenant_id = ? AND event_id = ? AND id = ? FOR UPDATE
                """, OFFER_ROW, tenant.toString(), event.toString(), offer.toString()).stream().findFirst();
    }

    Optional<ReservationView> byKey(TenantActor actor, String key) {
        return jdbc.query(RESERVATION_VIEW + " AND r.request_key = ?", RESERVATION_ROW,
                actor.tenantId().toString(), actor.accountId().toString(), key).stream().findFirst();
    }

    Optional<ReservationView> reservation(TenantActor actor, UUID id) {
        return jdbc.query(RESERVATION_VIEW + " AND r.id = ?", RESERVATION_ROW,
                actor.tenantId().toString(), actor.accountId().toString(), id.toString()).stream().findFirst();
    }

    List<ReservationView> mine(TenantActor actor) {
        return jdbc.query(RESERVATION_VIEW + " ORDER BY r.created_at DESC, r.id DESC LIMIT 50", RESERVATION_ROW,
                actor.tenantId().toString(), actor.accountId().toString());
    }

    boolean hasActive(TenantActor actor, UUID event) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ticket_reservation
                    WHERE tenant_id = ? AND event_id = ? AND buyer_id = ? AND active_slot = 1
                )
                """, Boolean.class, actor.tenantId().toString(), event.toString(), actor.accountId().toString()));
    }

    void insert(TenantActor actor, UUID id, UUID event, OfferView offer, String key, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO ticket_reservation (id, tenant_id, event_id, offer_id, buyer_id, request_key,
                                                state, price_fen, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'LOCKED', ?, ?)
                """, id.toString(), actor.tenantId().toString(), event.toString(), offer.id().toString(),
                actor.accountId().toString(), key, offer.priceFen(), Timestamp.from(expiresAt));
    }

    boolean decrement(UUID tenant, UUID event, UUID offer) {
        return jdbc.update("""
                UPDATE concert_offer SET available = available - 1
                WHERE tenant_id = ? AND event_id = ? AND id = ? AND available > 0
                """, tenant.toString(), event.toString(), offer.toString()) == 1;
    }

    List<DueReservation> due() {
        return jdbc.query("""
                SELECT id, tenant_id, event_id, offer_id FROM ticket_reservation
                WHERE state = 'LOCKED' AND expires_at <= CURRENT_TIMESTAMP(6)
                ORDER BY expires_at, id LIMIT 80
                """, (rs, row) -> new DueReservation(uuid(rs, "id"), uuid(rs, "tenant_id"),
                uuid(rs, "event_id"), uuid(rs, "offer_id")));
    }

    boolean expire(DueReservation ticket) {
        return jdbc.update("""
                UPDATE ticket_reservation SET state = 'EXPIRED'
                WHERE tenant_id = ? AND event_id = ? AND offer_id = ? AND id = ?
                  AND state = 'LOCKED' AND expires_at <= CURRENT_TIMESTAMP(6)
                """, ticket.tenantId().toString(), ticket.eventId().toString(), ticket.offerId().toString(),
                ticket.id().toString()) == 1;
    }

    boolean increment(DueReservation ticket) {
        return jdbc.update("""
                UPDATE concert_offer SET available = available + 1
                WHERE tenant_id = ? AND event_id = ? AND id = ? AND available < capacity
                """, ticket.tenantId().toString(), ticket.eventId().toString(), ticket.offerId().toString()) == 1;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
