package com.skyowl.common.event;

import java.io.Serializable;
import java.time.Instant;

/**
 * 跨服务传递的事件基类
 */
public abstract class BaseEvent implements Serializable {
    private String traceId;
    private String sourceService;
    private long eventTime;

    public BaseEvent() {
        this.eventTime = Instant.now().toEpochMilli();
    }

    public abstract String eventType();

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSourceService() { return sourceService; }
    public void setSourceService(String sourceService) { this.sourceService = sourceService; }
    public long getEventTime() { return eventTime; }
    public void setEventTime(long eventTime) { this.eventTime = eventTime; }
}