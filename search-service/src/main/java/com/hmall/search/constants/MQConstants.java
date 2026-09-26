package com.hmall.search.constants;

/**
 * 搜索服务消息队列常量，需与商品服务保持一致
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
