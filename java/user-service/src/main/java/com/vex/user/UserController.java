package com.vex.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody Map<String, Object> req) {
        log.info("HTTP /api/user/create called");
        return userService.createUser(
            (String) req.get("username"),
            (String) req.get("email"),
            (String) req.getOrDefault("phone", "")
        );
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return userService.listUsers(limit);
    }

    @GetMapping("/{userId}")
    public Map<String, Object> get(@PathVariable Long userId) {
        return userService.getUser(userId);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return userService.getStats();
    }
}