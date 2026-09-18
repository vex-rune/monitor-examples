package com.skyowl.common.event;

public class UserCreatedEvent extends BaseEvent {
    private Long userId;
    private String username;
    private String email;
    private String phone;

    @Override
    public String eventType() { return "UserCreated"; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}