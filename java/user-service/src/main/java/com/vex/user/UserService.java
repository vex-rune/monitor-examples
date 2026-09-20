package com.vex.user;

import com.vex.common.event.UserCreatedEvent;
import com.vex.common.mq.EventPublisher;
import com.vex.common.mq.MessageBusConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户中心服务
 * HTTP -> MySQL users 插入 -> Redis 缓存 -> MQ (USER_EXCHANGE) -> notification-service
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    public static final String SERVICE_NAME = "user-service";

    private final JdbcTemplate jdbc;
    private final EventPublisher eventPublisher;

    private final Counter createCounter;
    private final Counter createFailCounter;
    private final Counter publishCounter;
    private final Timer userOpTimer;

    public UserService(JdbcTemplate jdbc, EventPublisher eventPublisher, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.createCounter = Counter.builder("vex_user_create_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.createFailCounter = Counter.builder("vex_user_create_failed_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.publishCounter = Counter.builder("vex_user_publish_total")
                .tag("service", SERVICE_NAME).register(registry);
        this.userOpTimer = Timer.builder("vex_user_op_duration")
                .tag("service", SERVICE_NAME).register(registry);
    }

    public Map<String, Object> createUser(String username, String email, String phone) {
        return userOpTimer.record(() -> {
            log.info("Creating user: username={}, email={}", username, email);

            Long userId;
            try {
                userId = insertUser(username, email, phone);
            } catch (DuplicateKeyException dup) {
                createFailCounter.increment();
                log.warn("User already exists: username={}", username);
                throw new RuntimeException("Username already exists: " + username, dup);
            } catch (Exception e) {
                createFailCounter.increment();
                log.error("Failed to create user: username={}", username, e);
                throw new RuntimeException("Create user failed", e);
            }
            createCounter.increment();

            // Redis 缓存用户画像（OTel 自动拦截）
            log.debug("Caching user profile to Redis: userId={}", userId);
            cacheUserProfile(userId, username, email);

            // 发布事件到 MQ
            publishUserCreated(userId, username, email, phone);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", userId);
            result.put("username", username);
            result.put("email", email);
            result.put("phone", phone);
            return result;
        });
    }

    private Long insertUser(String username, String email, String phone) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users(username, email, phone) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, phone);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private void cacheUserProfile(Long userId, String username, String email) {
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void publishUserCreated(Long userId, String username, String email, String phone) {
        UserCreatedEvent event = new UserCreatedEvent();
        event.setUserId(userId);
        event.setUsername(username);
        event.setEmail(email);
        event.setPhone(phone);
        event.setSourceService(SERVICE_NAME);
        event.setTraceId(MDC.get("trace_id"));

        log.info("Publishing UserCreated event: userId={}, username={}", userId, username);
        eventPublisher.publish(
            MessageBusConfig.USER_EXCHANGE,
            MessageBusConfig.USER_CREATED_RK,
            event);
        publishCounter.increment();
    }

    public List<Map<String, Object>> listUsers(int limit) {
        return jdbc.queryForList(
            "SELECT id, username, email, phone, status, created_at FROM users ORDER BY id DESC LIMIT ?",
            limit);
    }

    public Map<String, Object> getUser(Long userId) {
        log.info("Querying user: userId={}", userId);
        try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return jdbc.queryForMap(
            "SELECT id, username, email, phone, status FROM users WHERE id = ?", userId);
    }

    public Map<String, Long> getStats() {
        return Map.of(
            "created", (long) createCounter.count(),
            "failed", (long) createFailCounter.count(),
            "published", (long) publishCounter.count()
        );
    }
}