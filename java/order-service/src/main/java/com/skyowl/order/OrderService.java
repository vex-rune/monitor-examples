package com.skyowl.order;

import com.skyowl.common.event.OrderCreatedEvent;
import com.skyowl.common.event.OrderPayingEvent;
import com.skyowl.common.event.PaymentResultEvent;
import com.skyowl.common.mq.EventPublisher;
import com.skyowl.common.mq.MessageBusConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单中心服务
 *
 * 完整流程:
 *   HTTP -> MySQL orders -> Redis (库存检查) -> MQ ORDER_CREATED -> payment-service
 *   HTTP -> MQ ORDER_PAYING -> payment-service
 *   监听 PAYMENT_RESULT -> 更新订单状态
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    public static final String SERVICE_NAME = "order-service";

    private final JdbcTemplate jdbc;
    private final EventPublisher eventPublisher;

    private final Counter createCounter;
    private final Counter payCounter;
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Timer opTimer;

    public OrderService(JdbcTemplate jdbc, EventPublisher eventPublisher, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.createCounter = Counter.builder("skyowl_order_create_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.payCounter = Counter.builder("skyowl_order_pay_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.cacheHit = Counter.builder("skyowl_order_cache_hit_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.cacheMiss = Counter.builder("skyowl_order_cache_miss_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.opTimer = Timer.builder("skyowl_order_op_duration")
                .tag("service", SERVICE_NAME).register(registry);
    }

    public Map<String, Object> createOrder(Long userId, String productName, int quantity) {
        return opTimer.record(() -> {
            String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 12);
            BigDecimal amount = computeAmount(productName, quantity);
            log.info("Creating order: orderNo={}, userId={}, product={}, qty={}, amount={}",
                orderNo, userId, productName, quantity, amount);

            // 1. 检查用户（直接查 user-service 数据库）
            if (!checkUser(userId)) {
                throw new RuntimeException("User not found: " + userId);
            }

            // 2. Redis 检查库存（OTel 自动拦截）
            int stock = checkStock(productName);
            if (stock < quantity) {
                cacheMiss.increment();
                throw new RuntimeException("Out of stock: " + productName);
            }
            cacheHit.increment();

            // 3. MySQL 插入订单
            insertOrder(orderNo, userId, productName, amount, quantity);
            createCounter.increment();

            // 4. Redis 扣减库存
            reduceStock(productName, quantity);

            // 5. 发布 OrderCreated 事件
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderNo(orderNo);
            event.setUserId(userId);
            event.setProductName(productName);
            event.setAmount(amount);
            event.setQuantity(quantity);
            event.setSourceService(SERVICE_NAME);
            event.setTraceId(MDC.get("trace_id"));
            publishOrderCreated(event);

            Map<String, Object> result = new HashMap<>();
            result.put("orderNo", orderNo);
            result.put("userId", userId);
            result.put("productName", productName);
            result.put("quantity", quantity);
            result.put("amount", amount);
            result.put("status", "CREATED");
            return result;
        });
    }

    private BigDecimal computeAmount(String product, int qty) {
        int unit = switch (product) {
            case "iPhone 15" -> 7999;
            case "MacBook Pro" -> 19999;
            case "AirPods Pro" -> 1899;
            case "iPad Mini" -> 3999;
            default -> 999;
        };
        return new BigDecimal(unit * qty);
    }

    private boolean checkUser(Long userId) {
        log.debug("Verifying user exists: userId={}", userId);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'",
            Integer.class, userId);
        return count != null && count > 0;
    }

    private int checkStock(String product) {
        try { Thread.sleep(5 + ThreadLocalRandom.current().nextInt(10)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int stock = ThreadLocalRandom.current().nextInt(50, 200);
        log.debug("Redis GET stock: product={}, stock={}", product, stock);
        return stock;
    }

    private void reduceStock(String product, int qty) {
        log.debug("Redis DECR stock: product={}, qty={}", product, qty);
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Transactional
    private void insertOrder(String orderNo, Long userId, String product,
                             BigDecimal amount, int qty) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders(order_no, user_id, product_name, amount, quantity, status) " +
                "VALUES (?, ?, ?, ?, ?, 'PENDING')",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo);
            ps.setLong(2, userId);
            ps.setString(3, product);
            ps.setBigDecimal(4, amount);
            ps.setInt(5, qty);
            return ps;
        }, kh);
        log.info("Order inserted: orderNo={}, amount={}", orderNo, amount);
    }

    private void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreated event: orderNo={}", event.getOrderNo());
        eventPublisher.publish(
            MessageBusConfig.ORDER_EXCHANGE,
            MessageBusConfig.ORDER_CREATED_RK,
            event);
    }

    /**
     * 监听支付结果 - 更新订单状态
     */
    @RabbitListener(queues = MessageBusConfig.PAYMENT_RESULT_QUEUE)
    public void onPaymentResult(PaymentResultEvent event) {
        log.info("Received PaymentResult event: paymentNo={}, orderNo={}, status={}",
            event.getPaymentNo(), event.getOrderNo(), event.getStatus());

        String newStatus = "SUCCESS".equals(event.getStatus()) ? "PAID" : "PAY_FAILED";
        int rows = jdbc.update(
            "UPDATE orders SET status = ?, updated_at = NOW() WHERE order_no = ?",
            newStatus, event.getOrderNo());

        payCounter.increment();
        log.info("Order status updated: orderNo={}, status={}, rows={}",
            event.getOrderNo(), newStatus, rows);
    }

    public Map<String, Object> payOrder(String orderNo) {
        log.info("Initiating payment for order: orderNo={}", orderNo);
        Map<String, Object> order = jdbc.queryForMap(
            "SELECT order_no, user_id, amount, status FROM orders WHERE order_no = ?", orderNo);

        OrderPayingEvent event = new OrderPayingEvent();
        event.setOrderNo((String) order.get("order_no"));
        event.setUserId(((Number) order.get("user_id")).longValue());
        event.setAmount((BigDecimal) order.get("amount"));
        event.setSourceService(SERVICE_NAME);
        event.setTraceId(MDC.get("trace_id"));

        eventPublisher.publish(
            MessageBusConfig.ORDER_EXCHANGE,
            MessageBusConfig.ORDER_PAYING_RK,
            event);

        log.info("OrderPaying event published: orderNo={}", orderNo);
        return Map.of("orderNo", orderNo, "action", "PAYING_DISPATCHED");
    }

    public List<Map<String, Object>> listOrders(int limit) {
        return jdbc.queryForList(
            "SELECT order_no, user_id, product_name, amount, quantity, status, created_at " +
            "FROM orders ORDER BY id DESC LIMIT ?", limit);
    }

    public Map<String, Object> getOrder(String orderNo) {
        return jdbc.queryForMap(
            "SELECT order_no, user_id, product_name, amount, quantity, status, created_at " +
            "FROM orders WHERE order_no = ?", orderNo);
    }

    public Map<String, Long> getStats() {
        return Map.of(
            "created", (long) createCounter.count(),
            "paid", (long) payCounter.count()
        );
    }
}