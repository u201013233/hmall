package com.hmall.trade.config;

import com.hmall.common.utils.UserContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 配置：接收消息时自动把消息头 user-info 中的用户写入 UserContext，
 * 监听方法内可直接通过 UserContext.getUser() 获取，无需手动解析消息；
 * 并在监听方法执行结束后清理 ThreadLocal，防止消费者线程复用导致串号。
 */
@Configuration
public class MqConfig {

    /**
     * 自定义监听容器工厂（bean 名固定为 rabbitListenerContainerFactory，覆盖 Spring Boot 默认值）。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 收到消息后、调用监听方法前：把消息头里的用户写回 UserContext
        factory.setAfterReceivePostProcessors(message -> {
            Long userId = (Long) message.getMessageProperties().getHeader("user-info");
            if (userId != null) {
                UserContext.setUser(userId);
            }
            return message;
        });
        // 监听方法执行结束后：清理 ThreadLocal
        factory.setContainerCustomizer(container -> container.setAdviceChain(
                (MethodInterceptor) invocation -> {
                    try {
                        return invocation.proceed();
                    } finally {
                        UserContext.removeUser();
                    }
                }));
        return factory;
    }
}
