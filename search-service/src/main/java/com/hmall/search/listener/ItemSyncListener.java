package com.hmall.search.listener;

import com.hmall.common.constants.MQConstants;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 监听商品服务的数据变更消息，同步索引库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemSyncListener {

    private final ISearchService searchService;

    /**
     * 商品新增、修改：重新拉取商品数据写入索引库
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.ITEM_UPSERT_QUEUE_NAME, durable = "true"),
            exchange = @Exchange(name = MQConstants.ITEM_EXCHANGE_NAME),
            key = {MQConstants.ITEM_INSERT_KEY, MQConstants.ITEM_UPDATE_KEY}
    ))
    public void listenItemUpsert(Long itemId) {
        log.debug("收到商品{}的新增/修改消息，开始同步索引库", itemId);
        searchService.saveItemDoc(itemId);
    }

    /**
     * 商品删除：删除索引库中的文档
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.ITEM_DELETE_QUEUE_NAME, durable = "true"),
            exchange = @Exchange(name = MQConstants.ITEM_EXCHANGE_NAME),
            key = MQConstants.ITEM_DELETE_KEY
    ))
    public void listenItemDelete(Long itemId) {
        log.debug("收到商品{}的删除消息，开始清理索引库", itemId);
        searchService.deleteItemDoc(itemId);
    }
}
