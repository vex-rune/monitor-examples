package com.vex.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return paymentService.listPayments(limit);
    }

    @GetMapping("/{paymentNo}")
    public Map<String, Object> get(@PathVariable String paymentNo) {
        return paymentService.getPayment(paymentNo);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return paymentService.getStats();
    }
}