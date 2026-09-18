package com.skyowl.common.event;

import java.math.BigDecimal;

public class OrderPayingEvent extends BaseEvent {
    private String orderNo;
    private Long userId;
    private BigDecimal amount;

    @Override
    public String eventType() { return "OrderPaying"; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}