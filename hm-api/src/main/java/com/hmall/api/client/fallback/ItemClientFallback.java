package com.hmall.api.client.fallback;

import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collection;
import java.util.List;

/**
 * ItemClient 的失败降级逻辑，在限流、线程隔离、熔断触发时执行。
 *
 * @author 虎哥
 */
@Slf4j
public class ItemClientFallback implements FallbackFactory<ItemClient> {
    @Override
    public ItemClient create(Throwable cause) {
        return new ItemClient() {
            @Override
            public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
                log.error("远程调用ItemClient#queryItemByIds方法出现异常，参数：{}", ids, cause);
                // 查询商品失败不影响购物车展示，返回空集合（购物车列表仍能正常返回，只是没有最新商品信息）
                return CollUtils.emptyList();
            }

            @Override
            public ItemDTO queryItemById(Long id) {
                log.error("远程调用ItemClient#queryItemById方法出现异常，参数：{}", id, cause);
                // 查询商品详情失败，返回null
                return null;
            }

            @Override
            public void deductStock(List<OrderDetailDTO> items) {
                // 写操作不能降级：扣减库存失败必须让调用方感知，否则会出现库存没扣、订单却创建成功的脏数据
                log.error("远程调用ItemClient#deductStock方法出现异常，参数：{}", items, cause);
                throw new BizIllegalException(cause);
            }

            @Override
            public void restoreStock(List<OrderDetailDTO> items) {
                // 写操作不能降级：恢复库存失败同样需要调用方感知
                log.error("远程调用ItemClient#restoreStock方法出现异常，参数：{}", items, cause);
                throw new BizIllegalException(cause);
            }
        };
    }
}
