package com.hmall.search.service;

import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.search.domain.query.ItemPageQuery;

import java.util.List;
import java.util.Map;

public interface ISearchService {

    /**
     * 搜索商品
     */
    PageDTO<ItemDTO> search(ItemPageQuery query);

    /**
     * 查询过滤项：统计当前搜索结果中包含的分类、品牌
     *
     * @return key为过滤字段名（category、brand），value为过滤项列表
     */
    Map<String, List<String>> filters(ItemPageQuery query);

    /**
     * 新增或修改索引库中的商品文档（商品已下架或不存在时会被删除）
     *
     * @param itemId 商品id
     */
    void saveItemDoc(Long itemId);

    /**
     * 删除索引库中的商品文档
     *
     * @param itemId 商品id
     */
    void deleteItemDoc(Long itemId);
}
