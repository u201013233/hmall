package com.hmall.common.constants;

/**
 * 消息队列常量。
 * 交换机、队列、routingKey 是生产端与消费端之间的契约，
 * 统一定义在公共模块里，避免两边各写一份导致改动不同步。
 */
public interface MQConstants {
    /**
     * 商品数据同步交换机
     */
    String ITEM_EXCHANGE_NAME = "item.direct";
    /**
     * 商品新增/修改队列
     */
    String ITEM_UPSERT_QUEUE_NAME = "item.upsert.queue";
    /**
     * 商品删除队列
     */
    String ITEM_DELETE_QUEUE_NAME = "item.delete.queue";
    /**
     * 商品新增 routingKey
     */
    String ITEM_INSERT_KEY = "item.insert";
    /**
     * 商品修改 routingKey
     */
    String ITEM_UPDATE_KEY = "item.update";
    /**
     * 商品删除 routingKey
     */
    String ITEM_DELETE_KEY = "item.delete";
}
