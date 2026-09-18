package com.skyowl.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return notificationService.listNotifications(limit);
    }

    @GetMapping("/user/{userId}")
    public List<Map<String, Object>> byUser(@PathVariable Long userId) {
        return notificationService.listByUser(userId);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return notificationService.getStats();
    }
}