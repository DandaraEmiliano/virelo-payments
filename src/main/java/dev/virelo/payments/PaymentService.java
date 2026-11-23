package dev.virelo.payments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
class PaymentService {
    private static final Duration CHARGE_LIFETIME = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    enum Method { PIX, CREDIT, DEBIT }
    enum Scenario { APPROVE, DECLINE }
    enum AttemptStatus { PENDING, APPROVED, DECLINED, SUPERSEDED }
    enum ChargeStatus { OPEN, PAID, EXPIRED }

    record Charge(UUID id, String description, long amountCents, Instant createdAt, Instant expiresAt,
                  UUID paidAttemptId) {
        ChargeStatus statusAt(Instant now) {
            if (paidAttemptId != null) {
                return ChargeStatus.PAID;
            }
            return expiresAt.isAfter(now) ? ChargeStatus.OPEN : ChargeStatus.EXPIRED;
        }
    }

    record ChargeView(UUID id, String description, long amountCents, Instant createdAt, Instant expiresAt,
                      ChargeStatus status) {
        static ChargeView of(Charge charge, Instant now) {
            return new ChargeView(charge.id(), charge.description(), charge.amountCents(), charge.createdAt(),
                    charge.expiresAt(), charge.statusAt(now));
        }
    }

    record Attempt(UUID id, Method method, Scenario scenario, AttemptStatus status,
                   Instant createdAt, Instant updatedAt) {}

    record Details(ChargeView charge, List<Attempt> attempts) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry metrics;

    PaymentService(JdbcClient jdbc, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.metrics = metrics;
    }

    List<ChargeView> list() {
        Instant now = now();
        return jdbc.sql("SELECT * FROM charges ORDER BY created_at DESC, id")
                .query((row, index) -> ChargeView.of(readCharge(row), now))
                .list();
    }

    @Transactional
    ChargeView create(String description, Long amountCents) {
        if (description == null || description.isBlank() || description.strip().length() > 100) {
            throw badRequest("Informe uma descrição de até 100 caracteres.");
        }
        if (amountCents == null || amountCents < 1 || amountCents > 99_999_999) {
            throw badRequest("Informe um valor entre R$ 0,01 e R$ 999.999,99.");
        }

        Instant now = now();
        Charge charge = new Charge(UUID.randomUUID(), description.strip(), amountCents, now,
                now.plus(CHARGE_LIFETIME), null);
        insertCharge(charge);
        return ChargeView.of(charge, now);
    }

    Details details(UUID chargeId) {
        // One statement gives a consistent snapshot of the charge and its attempts.
        var rows = jdbc.sql("""
                SELECT c.*, a.id AS attempt_id, a.method, a.scenario, a.status,
                       a.created_at AS attempt_created_at, a.updated_at AS attempt_updated_at
                FROM charges c
                LEFT JOIN payment_attempts a ON a.charge_id = c.id
                WHERE c.id = :chargeId
                ORDER BY a.created_at DESC, a.id
                """).param("chargeId", chargeId)
                .query((row, index) -> new ChargeAndAttempt(readCharge(row),
                        row.getObject("attempt_id") == null ? null : readAttempt(row)))
                .list();
        if (rows.isEmpty()) {
            throw notFound("Cobrança não encontrada.");
        }
        return new Details(ChargeView.of(rows.getFirst().charge(), now()),
                rows.stream().map(ChargeAndAttempt::attempt).filter(attempt -> attempt != null).toList());
    }

    private record ChargeAndAttempt(Charge charge, Attempt attempt) {}

    @Transactional
    Attempt start(UUID chargeId, UUID attemptId, Method method, Scenario scenario) {
        if (attemptId == null || method == null || scenario == null || method == Method.PIX && scenario == Scenario.DECLINE) {
            throw badRequest("Informe um meio e um cenário de pagamento válidos.");
        }
        Charge charge = lockCharge(chargeId);
        var existing = jdbc.sql("""
                SELECT id AS attempt_id, method, scenario, status, created_at AS attempt_created_at,
                       updated_at AS attempt_updated_at
                FROM payment_attempts WHERE charge_id = :chargeId AND id = :id
                """).param("chargeId", chargeId).param("id", attemptId)
                .query((row, index) -> readAttempt(row))
                .optional();
        if (existing.isPresent()) {
            Attempt replay = existing.get();
            if (replay.method() != method || replay.scenario() != scenario) {
                throw conflict("Esta chave já identifica outra tentativa.");
            }
            return replay;
        }

        Instant now = now();
        requireOpen(charge, now);
        AttemptStatus status = switch (method) {
            case PIX -> AttemptStatus.PENDING;
            case DEBIT, CREDIT -> scenario == Scenario.APPROVE ? AttemptStatus.APPROVED : AttemptStatus.DECLINED;
        };
        Attempt attempt = new Attempt(attemptId, method, scenario, status, now, now);
        insertAttempt(chargeId, attempt);
        if (status == AttemptStatus.APPROVED) {
            settle(chargeId, attemptId, now);
        }
        recordOutcome(chargeId, attempt);
        return attempt;
    }

    @Transactional
    Attempt confirmPix(UUID chargeId, UUID attemptId) {
        Charge charge = lockCharge(chargeId);
        Attempt attempt = jdbc.sql("""
                SELECT id AS attempt_id, method, scenario, status, created_at AS attempt_created_at,
                       updated_at AS attempt_updated_at
                FROM payment_attempts WHERE charge_id = :chargeId AND id = :id
                """).param("chargeId", chargeId).param("id", attemptId)
                .query((row, index) -> readAttempt(row)).optional()
                .orElseThrow(() -> notFound("Tentativa não encontrada."));

        if (attempt.method() != Method.PIX) {
            throw conflict("Esta tentativa não é um Pix.");
        }
        if (attempt.status() == AttemptStatus.APPROVED) {
            return attempt;
        }

        Instant now = now();
        requireOpen(charge, now);
        if (attempt.status() != AttemptStatus.PENDING) {
            throw conflict("Esta tentativa não está aguardando confirmação.");
        }

        jdbc.sql("""
                UPDATE payment_attempts SET status = 'APPROVED', updated_at = :now
                WHERE charge_id = :chargeId AND id = :id
                """).param("now", databaseTime(now)).param("chargeId", chargeId).param("id", attemptId).update();
        settle(chargeId, attemptId, now);
        Attempt approved = new Attempt(attemptId, Method.PIX, attempt.scenario(), AttemptStatus.APPROVED,
                attempt.createdAt(), now);
        recordOutcome(chargeId, approved);
        return approved;
    }

    private Charge lockCharge(UUID id) {
        return jdbc.sql("SELECT * FROM charges WHERE id = :id FOR UPDATE").param("id", id)
                .query((row, index) -> readCharge(row)).optional()
                .orElseThrow(() -> notFound("Cobrança não encontrada."));
    }

    private void settle(UUID chargeId, UUID attemptId, Instant now) {
        jdbc.sql("UPDATE charges SET paid_attempt_id = :attemptId WHERE id = :id")
                .param("attemptId", attemptId).param("id", chargeId).update();
        jdbc.sql("""
                UPDATE payment_attempts SET status = 'SUPERSEDED', updated_at = :now
                WHERE charge_id = :chargeId AND status = 'PENDING'
                """).param("now", databaseTime(now)).param("chargeId", chargeId).update();
    }

    private void requireOpen(Charge charge, Instant now) {
        switch (charge.statusAt(now)) {
            case PAID -> throw conflict("Esta cobrança já foi paga.");
            case EXPIRED -> throw conflict("Esta cobrança expirou.");
            case OPEN -> { }
        }
    }

    private void recordOutcome(UUID chargeId, Attempt attempt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.counter("virelo.payment.transitions", "method", attempt.method().name(),
                        "status", attempt.status().name()).increment();
                log.info("payment_attempt charge_id={} attempt_id={} method={} status={}",
                        chargeId, attempt.id(), attempt.method(), attempt.status());
            }
        });
    }

    void insertCharge(Charge charge) {
        jdbc.sql("""
                INSERT INTO charges (id, description, amount_cents, created_at, expires_at)
                VALUES (:id, :description, :amount, :created, :expires)
                """).param("id", charge.id()).param("description", charge.description())
                .param("amount", charge.amountCents()).param("created", databaseTime(charge.createdAt()))
                .param("expires", databaseTime(charge.expiresAt())).update();
    }

    private void insertAttempt(UUID chargeId, Attempt attempt) {
        jdbc.sql("""
                INSERT INTO payment_attempts (id, charge_id, method, scenario, status, created_at, updated_at)
                VALUES (:id, :chargeId, :method, :scenario, :status, :created, :updated)
                """).param("id", attempt.id()).param("chargeId", chargeId).param("method", attempt.method().name())
                .param("scenario", attempt.scenario().name()).param("status", attempt.status().name())
                .param("created", databaseTime(attempt.createdAt())).param("updated", databaseTime(attempt.updatedAt()))
                .update();
    }

    private static Charge readCharge(ResultSet row) throws SQLException {
        return new Charge(row.getObject("id", UUID.class), row.getString("description"), row.getLong("amount_cents"),
                readTime(row, "created_at"), readTime(row, "expires_at"), row.getObject("paid_attempt_id", UUID.class));
    }

    private static Attempt readAttempt(ResultSet row) throws SQLException {
        return new Attempt(row.getObject("attempt_id", UUID.class), Method.valueOf(row.getString("method")),
                Scenario.valueOf(row.getString("scenario")), AttemptStatus.valueOf(row.getString("status")),
                readTime(row, "attempt_created_at"), readTime(row, "attempt_updated_at"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant readTime(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
