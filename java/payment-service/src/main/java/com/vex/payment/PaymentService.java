package com.vex.payment;

import com.vex.common.event.OrderPayingEvent;
import com.vex.common.event.PaymentResultEvent;
import com.vex.common.mq.EventPublisher;
import com.vex.common.mq.MessageBusConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付中心服务
 *
 * 监听 ORDER_PAYING -> 处理支付 -> 发布 PAYMENT_RESULT -> order-service + notification-service
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    public static final String SERVICE_NAME = "payment-service";

    private final JdbcTemplate jdbc;
    private final EventPublisher eventPublisher;

    private final Counter paySuccessCounter;
    private final Counter payFailCounter;
    private final Counter publishCounter;
    private final Timer payTimer;

    public PaymentService(JdbcTemplate jdbc, EventPublisher eventPublisher, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.paySuccessCounter = Counter.builder("vex_payment_success_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.payFailCounter = Counter.builder("vex_payment_failed_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.publishCounter = Counter.builder("vex_payment_publish_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.payTimer = Timer.builder("vex_payment_op_duration")
                .tag("service", SERVICE_NAME).register(registry);
    }

    /**
     * 监听 OrderPaying -> 处理支付
     */
    @RabbitListener(queues = MessageBusConfig.ORDER_PAYING_QUEUE)
    public void onOrderPaying(OrderPayingEvent event) {
        log.info("Received OrderPaying event: orderNo={}, userId={}, amount={}",
            event.getOrderNo(), event.getUserId(), event.getAmount());

        // 设置 MDC 让 trace_id 跟随
        if (event.getTraceId() != null) {
            MDC.put("trace_id", event.getTraceId());
        }

        payTimer.record(() -> {
            String paymentNo = "PAY-" + UUID.randomUUID().toString().substring(0, 12);
            log.info("Processing payment: paymentNo={}, orderNo={}", paymentNo, event.getOrderNo());

            // 模拟第三方支付网关调用
            boolean success = processPayment(paymentNo, event);

            // 写 payments 表
            insertPayment(paymentNo, event, success ? "SUCCESS" : "FAILED");

            // 发布支付结果
            PaymentResultEvent result = new PaymentResultEvent();
            result.setPaymentNo(paymentNo);
            result.setOrderNo(event.getOrderNo());
            result.setUserId(event.getUserId());
            result.setAmount(event.getAmount());
            result.setStatus(success ? "SUCCESS" : "FAILED");
            result.setMessage(success ? "支付成功" : "支付网关超时");
            result.setSourceService(SERVICE_NAME);
            result.setTraceId(event.getTraceId());
            publishPaymentResult(result);

            if (success) {
                paySuccessCounter.increment();
                log.info("Payment success: paymentNo={}, orderNo={}", paymentNo, event.getOrderNo());
            } else {
                payFailCounter.increment();
                log.warn("Payment failed: paymentNo={}, orderNo={}", paymentNo, event.getOrderNo());
            }
        });
    }

    private boolean processPayment(String paymentNo, OrderPayingEvent event) {
        try {
            Thread.sleep(100 + ThreadLocalRandom.current().nextInt(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 5% 失败率
        return ThreadLocalRandom.current().nextDouble() >= 0.05;
    }

    @Transactional
    private void insertPayment(String paymentNo, OrderPayingEvent event, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO payments(payment_no, order_no, user_id, amount, status, paid_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW())",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, paymentNo);
            ps.setString(2, event.getOrderNo());
            ps.setLong(3, event.getUserId());
            ps.setBigDecimal(4, event.getAmount());
            ps.setString(5, status);
            return ps;
        }, kh);
        log.info("Payment record inserted: paymentNo={}, status={}", paymentNo, status);
    }

    private void publishPaymentResult(PaymentResultEvent result) {
        log.info("Publishing PaymentResult event: paymentNo={}, status={}",
            result.getPaymentNo(), result.getStatus());
        eventPublisher.publish(
            MessageBusConfig.PAYMENT_EXCHANGE,
            MessageBusConfig.PAYMENT_RESULT_RK,
            result);
        publishCounter.increment();
    }

    public List<Map<String, Object>> listPayments(int limit) {
        return jdbc.queryForList(
            "SELECT payment_no, order_no, user_id, amount, channel, status, paid_at, created_at " +
            "FROM payments ORDER BY id DESC LIMIT ?", limit);
    }

    public Map<String, Object> getPayment(String paymentNo) {
        return jdbc.queryForMap(
            "SELECT payment_no, order_no, user_id, amount, channel, status, paid_at, created_at " +
            "FROM payments WHERE payment_no = ?", paymentNo);
    }

    public Map<String, Long> getStats() {
        return Map.of(
            "success", (long) paySuccessCounter.count(),
            "failed", (long) payFailCounter.count(),
            "published", (long) publishCounter.count()
        );
    }
}