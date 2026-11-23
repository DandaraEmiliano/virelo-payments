package dev.virelo.payments;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static dev.virelo.payments.PaymentService.*;

@RestController
@RequestMapping("/api/charges")
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    record NewCharge(String description, Long amountCents) {}
    record NewAttempt(UUID id, Method method, Scenario scenario) {}

    @GetMapping
    List<ChargeView> list() {
        return payments.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChargeView create(@RequestBody NewCharge request) {
        return payments.create(request.description(), request.amountCents());
    }

    @GetMapping("/{chargeId}")
    Details details(@PathVariable UUID chargeId) {
        return payments.details(chargeId);
    }

    @PostMapping("/{chargeId}/attempts")
    Attempt pay(@PathVariable UUID chargeId, @RequestBody NewAttempt request) {
        return payments.start(chargeId, request.id(), request.method(), request.scenario());
    }

    @PostMapping(path = "/{chargeId}/attempts/{attemptId}/confirm", consumes = "application/json")
    Attempt confirm(@PathVariable UUID chargeId, @PathVariable UUID attemptId) {
        return payments.confirmPix(chargeId, attemptId);
    }
}

@Controller
class CheckoutPage {
    @GetMapping("/checkout/{id}")
    String checkout() {
        return "forward:/checkout.html";
    }
}
