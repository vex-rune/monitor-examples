package com.skyowl.common.mq;

import com.skyowl.common.event.BaseEvent;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 事件发布工具 - 自动设置 __TypeId__ 让消费者能反序列化为正确的子类
 */
@Component
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public EventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String exchange, String routingKey, BaseEvent event) {
        MessagePostProcessor typeIdProcessor = msg -> {
            msg.getMessageProperties().setHeader(
                "__TypeId__", event.getClass().getName());
            return msg;
        };
        rabbitTemplate.convertAndSend(exchange, routingKey, event, typeIdProcessor);
    }
}