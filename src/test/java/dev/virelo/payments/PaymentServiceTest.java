package dev.virelo.payments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ResponseStatusException;

import static dev.virelo.payments.PaymentService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=${VIRELO_TEST_DB_URL:jdbc:h2:mem:virelo-test}",
        "spring.datasource.username=${VIRELO_TEST_DB_USER:sa}",
        "spring.datasource.password=${VIRELO_TEST_DB_PASSWORD:}",
        "virelo.seed=false"
})
class PaymentServiceTest {
    @Autowired PaymentService payments;
    @Autowired AdjustableClock clock;

    @Test
    void declinedDebitCanBeFollowedByConfirmedPix() {
        UUID charge = payments.create("Teste de cobrança", 8990L).id();

        assertThat(payments.start(charge, UUID.randomUUID(), Method.DEBIT, Scenario.DECLINE).status())
                .isEqualTo(AttemptStatus.DECLINED);
        assertThat(payments.details(charge).charge().status()).isEqualTo(ChargeStatus.OPEN);

        UUID pix = payments.start(charge, UUID.randomUUID(), Method.PIX, Scenario.APPROVE).id();
        assertThat(payments.details(charge).attempts()).extracting(Attempt::status)
                .containsExactlyInAnyOrder(AttemptStatus.PENDING, AttemptStatus.DECLINED);
        var confirmation = payments.confirmPix(charge, pix);
        assertThat(payments.confirmPix(charge, pix)).isEqualTo(confirmation);
        assertThat(payments.details(charge).charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(payments.details(charge).attempts()).hasSize(2);
    }

    @Test
    void replayReturnsSameAttemptAndRejectsChangedPayload() {
        UUID charge = payments.create("Compra com cartão", 1590L).id();
        UUID request = UUID.randomUUID();
        var first = payments.start(charge, request, Method.CREDIT, Scenario.APPROVE);

        assertThat(payments.start(charge, request, Method.CREDIT, Scenario.APPROVE)).isEqualTo(first);
        assertThatThrownBy(() -> payments.start(charge, request, Method.DEBIT, Scenario.APPROVE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(payments.details(charge).attempts()).containsExactly(first);
    }

    @Test
    void oneOfTwoConcurrentPixConfirmationsSettlesTheCharge() throws Exception {
        UUID charge = payments.create("Confirmações concorrentes", 5000L).id();
        UUID first = payments.start(charge, UUID.randomUUID(), Method.PIX, Scenario.APPROVE).id();
        UUID second = payments.start(charge, UUID.randomUUID(), Method.PIX, Scenario.APPROVE).id();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(first, second).stream().map(id -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Não iniciou");
                try {
                    return payments.confirmPix(charge, id).status().name();
                } catch (ResponseStatusException error) {
                    return Integer.toString(error.getStatusCode().value());
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("APPROVED", "409");
        }
        assertThat(payments.details(charge).attempts()).extracting(Attempt::status)
                .containsExactlyInAnyOrder(AttemptStatus.APPROVED, AttemptStatus.SUPERSEDED);
    }

    @Test
    void expiredChargeRejectsNewAttemptsAndExistingPixConfirmation() {
        Instant original = clock.instant();
        try {
            UUID charge = payments.create("Cobrança expirada", 2000L).id();
            UUID pix = payments.start(charge, UUID.randomUUID(), Method.PIX, Scenario.APPROVE).id();
            clock.current = original.plusSeconds(86_400L);

            assertThat(payments.details(charge).charge().status()).isEqualTo(ChargeStatus.EXPIRED);
            assertThatThrownBy(() -> payments.confirmPix(charge, pix))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expirou");
            assertThatThrownBy(() -> payments.start(charge, UUID.randomUUID(), Method.DEBIT, Scenario.APPROVE))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expirou");
        } finally {
            clock.current = original;
        }
    }

    @Test
    void invalidAmountIsRejected() {
        assertThatThrownBy(() -> payments.create("Valor inválido", 0L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static class AdjustableClock extends Clock {
        private volatile Instant current = Instant.parse("2026-01-01T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
