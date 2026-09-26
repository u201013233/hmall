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
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    /**
     * 过滤项字段
     */
    private static final String FILTER_CATEGORY = "category";
    private static final String FILTER_BRAND = "brand";
    /**
     * 聚合返回的最大数量
     */
    private static final int AGG_SIZE = 20;
    /**
     * 广告字段与加权权重：isAD为true的商品得分乘以该权重，从而排在前面
     */
    private static final String FIELD_IS_AD = "isAD";
    private static final float AD_BOOST = 10f;

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

    @Override
    public Map<String, List<String>> filters(ItemPageQuery query) {
        try {
            Map<String, List<String>> filters = new LinkedHashMap<>(2);
            // 统计分类时排除分类自身的过滤条件，统计品牌时排除品牌自身的过滤条件，
            // 否则用户选中一个分类后，分类过滤项就只剩下它自己了
            filters.put(FILTER_CATEGORY, aggregateValues(query, FILTER_CATEGORY));
            filters.put(FILTER_BRAND, aggregateValues(query, FILTER_BRAND));
            return filters;
        } catch (IOException e) {
            throw new BizIllegalException("查询过滤项失败", e);
        }
    }

    /**
     * 对指定字段做terms聚合，返回聚合结果中的key列表
     */
    private List<String> aggregateValues(ItemPageQuery query, String field) throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest(ITEM_INDEX_NAME);
        String aggName = field + "_agg";
        // 2.组织请求参数：只查聚合不查文档，聚合条件是搜索条件（排除该字段自身的过滤条件）
        request.source(new SearchSourceBuilder()
                .query(buildQuery(query, field))
                .size(0)
                .aggregation(AggregationBuilders.terms(aggName).field(field).size(AGG_SIZE)));
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析聚合结果
        Terms terms = response.getAggregations().get(aggName);
        List<String> values = new ArrayList<>(terms.getBuckets().size());
        for (Terms.Bucket bucket : terms.getBuckets()) {
            values.add(bucket.getKeyAsString());
        }
        return values;
    }

    /**
     * 构建ES查询条件
     */
    private SearchSourceBuilder buildSourceBuilder(ItemPageQuery query) {
        SearchSourceBuilder source = new SearchSourceBuilder();
        // 1.查询条件：普通查询条件 + 算分函数（广告商品加权）
        source.query(buildFunctionScoreQuery(query));
        // 2.分页
        source.from(query.from()).size(query.getPageSize());
        // 3.排序
        applySort(source, query);
        // 4.返回精确的总条数
        source.trackTotalHits(true);
        return source;
    }

    /**
     * 构建算分函数查询：isAD为true的广告商品得分乘上权重，从而排到最前面
     */
    private FunctionScoreQueryBuilder buildFunctionScoreQuery(ItemPageQuery query) {
        return QueryBuilders.functionScoreQuery(
                buildQuery(query, null),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{
                        new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                // 过滤条件：广告商品
                                QueryBuilders.termQuery(FIELD_IS_AD, true),
                                // 算分函数：权重
                                ScoreFunctionBuilders.weightFactorFunction(AD_BOOST))
                });
    }

    /**
     * 设置排序条件。
     * 前端指定了排序字段时按字段排序；未指定时按相关性算分排序，
     * 这样function_score给广告商品加的分才能体现出来（竞价排名）。
     */
    private void applySort(SearchSourceBuilder source, ItemPageQuery query) {
        if (StrUtil.isNotBlank(query.getSortBy())) {
            source.sort(SortBuilders.fieldSort(resolveSortField(query.getSortBy()))
                    .order(resolveSortOrder(query)));
            return;
        }
        // 默认排序：按相关性算分倒序，广告商品得分高会排在前面
        source.sort(SortBuilders.scoreSort().order(SortOrder.DESC));
        // 分数相同时按更新时间倒序，保证分页结果稳定
        source.sort(SortBuilders.fieldSort(DEFAULT_SORT_FIELD).order(SortOrder.DESC));
    }

    /**
     * 构建ES的bool查询条件
     *
     * @param excludeFilterField 需要排除的过滤字段，聚合时传入自身字段名，普通搜索传null
     */
    private BoolQueryBuilder buildQuery(ItemPageQuery query, String excludeFilterField) {
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        // 1.关键字：对商品名称做分词匹配
        if (StrUtil.isNotBlank(query.getKey())) {
            boolQuery.must(QueryBuilders.matchQuery("name", query.getKey()));
        }
        // 2.过滤条件：分类
        if (StrUtil.isNotBlank(query.getCategory()) && !FILTER_CATEGORY.equals(excludeFilterField)) {
            boolQuery.filter(QueryBuilders.termQuery(FILTER_CATEGORY, query.getCategory()));
        }
        // 3.过滤条件：品牌
        if (StrUtil.isNotBlank(query.getBrand()) && !FILTER_BRAND.equals(excludeFilterField)) {
            boolQuery.filter(QueryBuilders.termQuery(FILTER_BRAND, query.getBrand()));
        }
        // 4.过滤条件：价格区间
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
        return boolQuery;
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
