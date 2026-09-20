package com.vex.common.event;

import java.math.BigDecimal;

public class OrderCreatedEvent extends BaseEvent {
    private String orderNo;
    private Long userId;
    private String productName;
    private BigDecimal amount;
    private Integer quantity;

    @Override
    public String eventType() { return "OrderCreated"; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}