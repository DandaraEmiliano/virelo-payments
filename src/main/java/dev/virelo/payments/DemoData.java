package dev.virelo.payments;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static dev.virelo.payments.PaymentService.*;

@Component
@ConditionalOnProperty(name = "virelo.seed", havingValue = "true")
class DemoData implements ApplicationRunner {
    private final PaymentService payments;
    private final Clock clock;

    DemoData(PaymentService payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!payments.list().isEmpty()) {
            return;
        }

        var current = clock.instant();
        var historical = current.minus(Duration.ofDays(2));
        payments.insertCharge(new Charge(UUID.randomUUID(), "Reserva de consultoria", 5400,
                historical, historical.plus(Duration.ofHours(24)), null));
        var paid = payments.create("Kit papelaria artesanal", 12990L);
        payments.start(paid.id(), UUID.randomUUID(), Method.CREDIT, Scenario.APPROVE);
        payments.create("E-book de fotografia", 4900L);
        payments.create("Oficina de cerâmica", 8990L);
    }
}
