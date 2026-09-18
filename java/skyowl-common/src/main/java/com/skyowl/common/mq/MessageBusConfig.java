package com.skyowl.common.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4 个服务共用消息总线配置
 *
 * 流程:
 *   user-service  -- UserCreated --> user.exchange  --> user.created.queue / notification
 *   order-service -- OrderCreated --> order.exchange --> order.created.queue / notification
 *   order-service -- OrderPaying   --> order.exchange --> order.paying.queue / payment + notification
 *   payment-service -- PaymentResult --> payment.exchange --> payment.result.queue / order + notification
 */
@Configuration
public class MessageBusConfig {

    // ============ 用户中心 ============
    public static final String USER_EXCHANGE = "skyowl.user.exchange";
    public static final String USER_CREATED_QUEUE = "skyowl.user.created.queue";
    public static final String USER_CREATED_RK = "skyowl.user.created";

    // ============ 订单中心 ============
    public static final String ORDER_EXCHANGE = "skyowl.order.exchange";
    public static final String ORDER_CREATED_QUEUE = "skyowl.order.created.queue";
    public static final String ORDER_CREATED_RK = "skyowl.order.created";
    public static final String ORDER_PAYING_QUEUE = "skyowl.order.paying.queue";
    public static final String ORDER_PAYING_RK = "skyowl.order.paying";

    // ============ 支付中心 ============
    public static final String PAYMENT_EXCHANGE = "skyowl.payment.exchange";
    public static final String PAYMENT_RESULT_QUEUE = "skyowl.payment.result.queue";
    public static final String PAYMENT_RESULT_RK = "skyowl.payment.result";

    // ============ 通知中心 ============
    public static final String NOTIFICATION_QUEUE = "skyowl.notification.queue";

    // ============== Exchange ==============
    @Bean
    public TopicExchange userExchange() { return new TopicExchange(USER_EXCHANGE, true, false); }

    @Bean
    public TopicExchange orderExchange() { return new TopicExchange(ORDER_EXCHANGE, true, false); }

    @Bean
    public TopicExchange paymentExchange() { return new TopicExchange(PAYMENT_EXCHANGE, true, false); }

    // ============== Queue ==============
    @Bean
    public Queue userCreatedQueue() { return QueueBuilder.durable(USER_CREATED_QUEUE).build(); }

    @Bean
    public Queue orderCreatedQueue() { return QueueBuilder.durable(ORDER_CREATED_QUEUE).build(); }

    @Bean
    public Queue orderPayingQueue() { return QueueBuilder.durable(ORDER_PAYING_QUEUE).build(); }

    @Bean
    public Queue paymentResultQueue() { return QueueBuilder.durable(PAYMENT_RESULT_QUEUE).build(); }

    @Bean
    public Queue notificationQueue() { return QueueBuilder.durable(NOTIFICATION_QUEUE).build(); }

    // ============== Binding ==============
    @Bean
    public Binding userCreatedBinding() {
        return BindingBuilder.bind(userCreatedQueue()).to(userExchange()).with(USER_CREATED_RK);
    }

    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder.bind(orderCreatedQueue()).to(orderExchange()).with(ORDER_CREATED_RK);
    }

    @Bean
    public Binding orderPayingBinding() {
        return BindingBuilder.bind(orderPayingQueue()).to(orderExchange()).with(ORDER_PAYING_RK);
    }

    @Bean
    public Binding paymentResultBinding() {
        return BindingBuilder.bind(paymentResultQueue()).to(paymentExchange()).with(PAYMENT_RESULT_RK);
    }

    // 通知中心订阅所有事件
    @Bean
    public Binding notificationUserBinding() {
        return BindingBuilder.bind(notificationQueue()).to(userExchange()).with(USER_CREATED_RK);
    }
    @Bean
    public Binding notificationOrderCreatedBinding() {
        return BindingBuilder.bind(notificationQueue()).to(orderExchange()).with(ORDER_CREATED_RK);
    }
    @Bean
    public Binding notificationOrderPayingBinding() {
        return BindingBuilder.bind(notificationQueue()).to(orderExchange()).with(ORDER_PAYING_RK);
    }
    @Bean
    public Binding notificationPaymentBinding() {
        return BindingBuilder.bind(notificationQueue()).to(paymentExchange()).with(PAYMENT_RESULT_RK);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter conv) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(conv);
        return t;
    }
}