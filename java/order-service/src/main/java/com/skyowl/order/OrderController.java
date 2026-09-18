package com.skyowl.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody Map<String, Object> req) {
        log.info("HTTP /api/order/create called");
        return orderService.createOrder(
            ((Number) req.getOrDefault("userId", 1L)).longValue(),
            (String) req.getOrDefault("productName", "iPhone 15"),
            (int) req.getOrDefault("quantity", 1)
        );
    }

    @PostMapping("/pay/{orderNo}")
    public Map<String, Object> pay(@PathVariable String orderNo) {
        log.info("HTTP /api/order/pay called: orderNo={}", orderNo);
        return orderService.payOrder(orderNo);
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return orderService.listOrders(limit);
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> get(@PathVariable String orderNo) {
        return orderService.getOrder(orderNo);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return orderService.getStats();
    }
}