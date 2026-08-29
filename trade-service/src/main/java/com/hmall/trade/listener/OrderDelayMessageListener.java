package com.hmall.trade.listener;

import com.hmall.api.client.PayClient;
import com.hmall.api.dto.PayOrderDTO;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    private final IOrderService orderService;
    private final PayClient payClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.DELAY_ORDER_QUEUE_NAME, durable = "true"),
            exchange = @Exchange(name = MQConstants.DELAY_EXCHANGE_NAME, delayed = "true"),
            key = MQConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(Long orderId) {
        log.debug("收到订单{}的延迟检查消息，开始处理（exchange：{}，queue：{}）",
                orderId, MQConstants.DELAY_EXCHANGE_NAME, MQConstants.DELAY_ORDER_QUEUE_NAME);
        // 1.查询订单
        Order order = orderService.getById(orderId);
        log.debug("订单{}查询结果：订单状态={}（1=未付款），订单金额={}分，下单用户={}",
                orderId, order == null ? null : order.getStatus(),
                order == null ? null : order.getTotalFee(),
                order == null ? null : order.getUserId());
        // 2.检测订单状态，判断是否已支付
        if (order == null || order.getStatus() != 1) {
            // 订单不存在或者已经支付/关闭
            log.debug("订单{}不存在或已非未付款状态（status={}），无需处理", orderId, order == null ? null : order.getStatus());
            return;
        }
        // 3.未支付，需要查询支付流水状态
        PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(orderId);
        log.debug("订单{}未付款，调用pay-service查询支付流水：支付单id={}，支付单号={}，支付流水状态={}（3=支付成功），金额={}分",
                orderId,
                payOrder == null ? null : payOrder.getId(),
                payOrder == null ? null : payOrder.getPayOrderNo(),
                payOrder == null ? null : payOrder.getStatus(),
                payOrder == null ? null : payOrder.getAmount());
        // 4.判断是否支付
        if (payOrder != null && payOrder.getStatus() == 3) {
            // 4.1.已支付，标记订单状态为已支付
            log.debug("订单{}支付流水状态为{}，判定已支付，更新订单为已支付", orderId, payOrder.getStatus());
            orderService.markOrderPaySuccess(orderId);
        } else {
            // 4.2.未支付，取消订单，恢复库存
            log.debug("订单{}支付流水不存在或未支付（payOrder={}），判定超时未支付，执行取消订单并恢复库存",
                    orderId, payOrder == null ? null : payOrder.getStatus());
            orderService.cancelOrder(orderId);
        }
    }
}
