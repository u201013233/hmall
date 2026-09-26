package com.hmall.item.config;

import com.hmall.item.constants.MQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品数据同步的交换机、队列与绑定关系。
 * 生产端也声明一次，避免搜索服务尚未启动时发送消息失败。
 */
@Configuration
public class MqConfig {

    /**
     * 商品同步交换机
     */
    @Bean
    public DirectExchange itemExchange() {
        return new DirectExchange(MQConstants.ITEM_EXCHANGE_NAME);
    }

    /**
     * 新增/修改队列
     */
    @Bean
    public Queue itemUpsertQueue() {
        return new Queue(MQConstants.ITEM_UPSERT_QUEUE_NAME, true);
    }

    /**
     * 删除队列
     */
    @Bean
    public Queue itemDeleteQueue() {
        return new Queue(MQConstants.ITEM_DELETE_QUEUE_NAME, true);
    }

    /**
     * 新增消息绑定到新增/修改队列
     */
    @Bean
    public Binding itemInsertBinding() {
        return BindingBuilder.bind(itemUpsertQueue()).to(itemExchange())
                .with(MQConstants.ITEM_INSERT_KEY);
    }

    /**
     * 修改消息绑定到新增/修改队列
     */
    @Bean
    public Binding itemUpdateBinding() {
        return BindingBuilder.bind(itemUpsertQueue()).to(itemExchange())
                .with(MQConstants.ITEM_UPDATE_KEY);
    }

    /**
     * 删除消息绑定到删除队列
     */
    @Bean
    public Binding itemDeleteBinding() {
        return BindingBuilder.bind(itemDeleteQueue()).to(itemExchange())
                .with(MQConstants.ITEM_DELETE_KEY);
    }
}
