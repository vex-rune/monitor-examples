package com.skyowl.common.event;

import java.math.BigDecimal;

public class PaymentResultEvent extends BaseEvent {
    private String paymentNo;
    private String orderNo;
    private Long userId;
    private BigDecimal amount;
    private String status;  // SUCCESS / FAILED
    private String message;

    @Override
    public String eventType() { return "PaymentResult"; }

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}