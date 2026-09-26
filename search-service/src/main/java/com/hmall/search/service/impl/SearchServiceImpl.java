package com.hmall.search.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.search.domain.po.ItemDoc;
import com.hmall.search.domain.query.ItemPageQuery;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    /**
     * 商品索引库名称
     */
    private static final String ITEM_INDEX_NAME = "items";
    /**
     * 允许排序的字段：前端字段名 -> 索引库字段名，其它字段一律走默认排序，避免排序报错
     */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "sold", "sold",
            "price", "price",
            "commentCount", "commentCount",
            "comment_count", "commentCount",
            "updateTime", "updateTime",
            "update_time", "updateTime"
    );
    /**
     * 默认排序字段
     */
    private static final String DEFAULT_SORT_FIELD = "updateTime";

    private final RestHighLevelClient client;
    private final ItemClient itemClient;

    @Override
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        // 1.准备Request
        SearchRequest request = new SearchRequest(ITEM_INDEX_NAME);
        // 2.准备请求参数：查询条件、分页、排序
        request.source(buildSourceBuilder(query));
        try {
            // 3.发送请求
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);
            // 4.解析结果
            return buildPageDTO(response, query);
        } catch (IOException e) {
            throw new BizIllegalException("搜索商品失败", e);
        }
    }

    @Override
    public void saveItemDoc(Long itemId) {
        // 1.远程查询商品详情
        ItemDTO item = itemClient.queryItemById(itemId);
        // 2.商品不存在或已下架，直接从索引库中删除
        if (item == null || item.getStatus() == null || item.getStatus() != 1) {
            log.debug("商品{}不存在或已下架，移除索引库中的文档", itemId);
            deleteItemDoc(itemId);
            return;
        }
        // 3.转换为索引库文档
        ItemDoc itemDoc = BeanUtil.copyProperties(item, ItemDoc.class);
        if (StrUtil.isBlank(itemDoc.getId())) {
            itemDoc.setId(String.valueOf(itemId));
        }
        // 4.写入索引库（id已存在时即为全量修改）
        IndexRequest request = new IndexRequest(ITEM_INDEX_NAME).id(itemDoc.getId())
                .source(JSONUtil.toJsonStr(itemDoc), XContentType.JSON);
        try {
            client.index(request, RequestOptions.DEFAULT);
            log.debug("商品{}索引库文档同步完成", itemId);
        } catch (IOException e) {
            throw new BizIllegalException("同步商品索引失败", e);
        }
    }

    @Override
    public void deleteItemDoc(Long itemId) {
        String docId = String.valueOf(itemId);
        try {
            // 1.文档不存在时无需删除
            if (!client.exists(new GetRequest(ITEM_INDEX_NAME, docId), RequestOptions.DEFAULT)) {
                return;
            }
            // 2.删除文档
            client.delete(new DeleteRequest(ITEM_INDEX_NAME, docId), RequestOptions.DEFAULT);
            log.debug("商品{}索引库文档删除完成", itemId);
        } catch (IOException e) {
            throw new BizIllegalException("删除商品索引失败", e);
        }
    }

    /**
     * 构建ES查询条件
     */
    private SearchSourceBuilder buildSourceBuilder(ItemPageQuery query) {
        SearchSourceBuilder source = new SearchSourceBuilder();
        // 1.关键字：对商品名称做分词匹配
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        if (StrUtil.isNotBlank(query.getKey())) {
            boolQuery.must(QueryBuilders.matchQuery("name", query.getKey()));
        }
        // 2.过滤条件：分类、品牌
        if (StrUtil.isNotBlank(query.getCategory())) {
            boolQuery.filter(QueryBuilders.termQuery("category", query.getCategory()));
        }
        if (StrUtil.isNotBlank(query.getBrand())) {
            boolQuery.filter(QueryBuilders.termQuery("brand", query.getBrand()));
        }
        // 3.过滤条件：价格区间
        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("price");
            if (query.getMinPrice() != null) {
                rangeQuery.gte(query.getMinPrice());
            }
            if (query.getMaxPrice() != null) {
                rangeQuery.lte(query.getMaxPrice());
            }
            boolQuery.filter(rangeQuery);
        }
        source.query(boolQuery);
        // 4.分页
        source.from(query.from()).size(query.getPageSize());
        // 5.排序
        source.sort(SortBuilders.fieldSort(resolveSortField(query.getSortBy()))
                .order(resolveSortOrder(query)));
        // 6.返回精确的总条数
        source.trackTotalHits(true);
        return source;
    }

    /**
     * 前端排序字段转换为索引库字段，不支持的字段使用默认排序
     */
    private String resolveSortField(String sortBy) {
        if (StrUtil.isBlank(sortBy)) {
            return DEFAULT_SORT_FIELD;
        }
        return SORT_FIELDS.getOrDefault(sortBy, DEFAULT_SORT_FIELD);
    }

    /**
     * 未指定排序字段时默认按更新时间倒序
     */
    private SortOrder resolveSortOrder(ItemPageQuery query) {
        if (StrUtil.isBlank(query.getSortBy())) {
            return SortOrder.DESC;
        }
        return Boolean.FALSE.equals(query.getIsAsc()) ? SortOrder.DESC : SortOrder.ASC;
    }

    /**
     * 解析ES响应结果
     */
    private PageDTO<ItemDTO> buildPageDTO(SearchResponse response, ItemPageQuery query) {
        SearchHits searchHits = response.getHits();
        long total = searchHits.getTotalHits() == null ? 0L : searchHits.getTotalHits().value;
        List<ItemDTO> list = new ArrayList<>(searchHits.getHits().length);
        for (SearchHit hit : searchHits.getHits()) {
            ItemDoc itemDoc = JSONUtil.toBean(hit.getSourceAsString(), ItemDoc.class);
            list.add(BeanUtil.copyProperties(itemDoc, ItemDTO.class));
        }
        long pages = total == 0 ? 0L : (total + query.getPageSize() - 1) / query.getPageSize();
        return new PageDTO<>(total, pages, list);
    }
}
