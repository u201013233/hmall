package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.client.CartClient;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品（通过 Feign 远程调用 item-service）
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);

        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.清理购物车商品（通过 Feign 远程调用 cart-service）
        cartClient.deleteCartItemByIds(itemIds);

        // 4.扣减库存（通过 Feign 远程调用 item-service）
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }

        // 5.发送延迟消息：10秒后查询订单支付状态，超时未支付则关闭订单（生产环境建议15分钟）
        rabbitTemplate.convertAndSend(MQConstants.DELAY_EXCHANGE_NAME, MQConstants.DELAY_ORDER_KEY, order.getId(),
                message -> {
                    message.getMessageProperties().setDelay(30000);
                    return message;
                });
        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        log.info("收到支付成功通知，开始更新订单状态，订单id：{}，操作人用户id：{}", orderId, UserContext.getUser());
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        boolean success = updateById(order);
        log.info("订单状态更新完成，订单id：{}，结果：{}", orderId, success ? "成功" : "失败");
    }

    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        // 1.幂等判断：订单不存在或已非未付款状态，直接跳过
        Order order = getById(orderId);
        if (order == null || order.getStatus() != 1) {
            log.info("订单{}不存在或已非未付款状态（status={}），跳过取消", orderId, order == null ? null : order.getStatus());
            return;
        }
        // 2.关闭订单：带状态条件更新，防止并发重复处理
        boolean updated = lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, 1)
                .set(Order::getStatus, 5)
                .set(Order::getCloseTime, LocalDateTime.now())
                .update();
        if (!updated) {
            log.info("订单{}状态已变化，取消失败（幂等保护）", orderId);
            return;
        }
        // 3.恢复已扣减的库存
        List<OrderDetail> details = detailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();
        if (details == null || details.isEmpty()) {
            log.info("订单{}已关闭，无订单明细，无需恢复库存", orderId);
            return;
        }
        List<OrderDetailDTO> restoreItems = details.stream()
                .map(d -> new OrderDetailDTO().setItemId(d.getItemId()).setNum(d.getNum()))
                .collect(Collectors.toList());
        itemClient.restoreStock(restoreItems);
        log.info("订单{}超时未支付，订单已关闭，库存已恢复", orderId);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
