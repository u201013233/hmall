package com.hmall.pay.config;

import com.hmall.common.utils.UserContext;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 配置：发送消息时自动把当前登录用户写入消息头 user-info，
 * 消费端通过 UserContext 无感知获取，业务代码无需手动传递用户信息。
 */
@Configuration
public class MqConfig {

    /**
     * 自定义 RabbitTemplate：保留 Spring Boot 默认配置，
     * 并在每条消息发送前自动写入当前登录用户到消息头 user-info。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory, RabbitTemplateConfigurer configurer) {
        RabbitTemplate template = new RabbitTemplate();
        // 应用 Spring Boot 对 RabbitTemplate 的默认配置（消息转换器等）
        configurer.configure(template, connectionFactory);
        template.setBeforePublishPostProcessors(
                (MessagePostProcessor) message -> {
                    Long userId = UserContext.getUser();
                    if (userId != null) {
                        message.getMessageProperties().setHeader("user-info", userId);
                    }
                    return message;
                });
        return template;
    }
}
