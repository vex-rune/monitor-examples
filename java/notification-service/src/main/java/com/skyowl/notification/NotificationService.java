package com.skyowl.notification;

import com.skyowl.common.event.*;
import com.skyowl.common.mq.MessageBusConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 通知中心服务
 *
 * 订阅所有事件 (User/Order/Payment) -> 写 notifications 表 -> 模拟推送
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final String SERVICE_NAME = "notification-service";

    private final JdbcTemplate jdbc;
    private final Counter receivedCounter;
    private final Counter sentCounter;
    private final Counter failCounter;

    public NotificationService(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.receivedCounter = Counter.builder("skyowl_notification_received_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.sentCounter = Counter.builder("skyowl_notification_sent_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.failCounter = Counter.builder("skyowl_notification_failed_total")
                .tag("service", SERVICE_NAME).register(registry);
    }

    /**
     * 接收所有事件 - 使用 BaseEvent 多态
     */
    @RabbitListener(queues = MessageBusConfig.NOTIFICATION_QUEUE)
    public void handleNotification(BaseEvent event) {
        if (event == null) return;
        receivedCounter.increment();

        String eventType = event.eventType();
        String sourceService = event.getSourceService();
        String traceId = event.getTraceId();

        // 恢复 trace context
        if (traceId != null) MDC.put("trace_id", traceId);

        try {
            log.info("Received notification: eventType={}, source={}", eventType, sourceService);

            switch (eventType) {
                case "UserCreated" -> handleUserCreated((UserCreatedEvent) event);
                case "OrderCreated" -> handleOrderCreated((OrderCreatedEvent) event);
                case "OrderPaying" -> handleOrderPaying((OrderPayingEvent) event);
                case "PaymentResult" -> handlePaymentResult((PaymentResultEvent) event);
                default -> log.warn("Unknown event type: {}", eventType);
            }
        } catch (ClassCastException cce) {
            log.error("Failed to cast event: {}", eventType, cce);
        } finally {
            MDC.clear();
        }
    }

    private void handleUserCreated(UserCreatedEvent e) {
        log.info("[UserCreated] userId={}, welcome message queued", e.getUserId());
        saveNotification(e.getUserId(), null, "USER_WELCOME",
            "欢迎加入", "Hi " + e.getUsername() + ", 欢迎使用 Skyowl 服务！");
    }

    private void handleOrderCreated(OrderCreatedEvent e) {
        log.info("[OrderCreated] orderNo={}, product={}, amount={}",
            e.getOrderNo(), e.getProductName(), e.getAmount());
        saveNotification(e.getUserId(), e.getOrderNo(), "ORDER_CREATED",
            "订单已创建",
            "您的订单 " + e.getOrderNo() + " 已创建，商品：" + e.getProductName()
                + "，金额：" + e.getAmount());
    }

    private void handleOrderPaying(OrderPayingEvent e) {
        log.info("[OrderPaying] orderNo={}", e.getOrderNo());
        saveNotification(e.getUserId(), e.getOrderNo(), "ORDER_PAYING",
            "订单支付中", "您的订单 " + e.getOrderNo() + " 正在处理支付...");
    }

    private void handlePaymentResult(PaymentResultEvent e) {
        log.info("[PaymentResult] orderNo={}, status={}", e.getOrderNo(), e.getStatus());
        boolean success = "SUCCESS".equals(e.getStatus());
        saveNotification(e.getUserId(), e.getOrderNo(),
            success ? "PAYMENT_SUCCESS" : "PAYMENT_FAILED",
            success ? "支付成功" : "支付失败",
            success ? "您的订单 " + e.getOrderNo() + " 已支付成功！"
                    : "您的订单 " + e.getOrderNo() + " 支付失败：" + e.getMessage());
    }

    @Transactional
    private void saveNotification(Long userId, String orderNo, String type,
                                  String title, String content) {
        try {
            Thread.sleep(20 + ThreadLocalRandom.current().nextInt(30));
            if (ThreadLocalRandom.current().nextDouble() < 0.03) {
                failCounter.increment();
                throw new RuntimeException("推送服务暂时不可用");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO notifications(user_id, order_no, type, title, content, channel) " +
                "VALUES (?, ?, ?, ?, ?, 'PUSH')",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, orderNo);
            ps.setString(3, type);
            ps.setString(4, title);
            ps.setString(5, content);
            return ps;
        }, kh);
        sentCounter.increment();
        log.debug("Notification saved and sent: type={}, userId={}", type, userId);
    }

    public List<Map<String, Object>> listNotifications(int limit) {
        return jdbc.queryForList(
            "SELECT id, user_id, order_no, type, title, content, read_status, created_at " +
            "FROM notifications ORDER BY id DESC LIMIT ?", limit);
    }

    public List<Map<String, Object>> listByUser(Long userId) {
        return jdbc.queryForList(
            "SELECT id, order_no, type, title, content, read_status, created_at " +
            "FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 50", userId);
    }

    public Map<String, Long> getStats() {
        return Map.of(
            "received", (long) receivedCounter.count(),
            "sent", (long) sentCounter.count(),
            "failed", (long) failCounter.count()
        );
    }
}