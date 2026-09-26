package com.hmall.search.es;

import cn.hutool.json.JSONUtil;
import com.hmall.common.utils.CollUtils;
import com.hmall.search.domain.po.ItemDoc;
import org.apache.http.HttpHost;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * RestClient 查询：叶子查询、复合查询、排序分页、高亮
 */
public class SearchTest {

    private RestHighLevelClient client;

    @BeforeEach
    void setUp() {
        this.client = new RestHighLevelClient(RestClient.builder(
                HttpHost.create("http://localhost:9200")
        ));
    }

    @AfterEach
    void tearDown() throws IOException {
        this.client.close();
    }

    // ==================== 2.1 快速入门 ====================

    @Test
    void testMatchAll() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数，match_all即无条件查询
        // 注意：ES默认只统计前1万条，需要精确总数时可以加 request.source().trackTotalHits(true)
        request.source().query(QueryBuilders.matchAllQuery());
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    // ==================== 2.2 叶子查询 ====================

    @Test
    void testMatch() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数，match会对搜索条件分词后去倒排索引中匹配
        request.source().query(QueryBuilders.matchQuery("name", "华为手机"));
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    @Test
    void testMultiMatch() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数，multi_match可以指定多个字段
        request.source().query(QueryBuilders.multiMatchQuery("华为", "name", "category"));
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    @Test
    void testRange() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数，range做范围查询，价格单位是分
        request.source().query(QueryBuilders.rangeQuery("price").gte(10000).lte(30000));
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    @Test
    void testTerm() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数，term是精确匹配，适合keyword类型字段
        request.source().query(QueryBuilders.termQuery("brand", "华为"));
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    // ==================== 2.3 复合查询 ====================

    @Test
    void testBool() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数
        // 2.1.准备bool查询
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        // 2.2.关键字搜索
        bool.must(QueryBuilders.matchQuery("name", "华为"));
        // 2.3.品牌过滤
        bool.filter(QueryBuilders.termQuery("brand", "华为"));
        // 2.4.价格过滤，3000元以内
        bool.filter(QueryBuilders.rangeQuery("price").lte(300000));
        request.source().query(bool);
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    // ==================== 2.4 排序和分页 ====================

    @Test
    void testPageAndSort() throws IOException {
        int pageNo = 1, pageSize = 5;

        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数
        // 2.1.搜索条件参数
        request.source().query(QueryBuilders.matchQuery("name", "手机"));
        // 2.2.排序参数，按价格升序
        request.source().sort("price", SortOrder.ASC);
        // 2.3.分页参数
        request.source().from((pageNo - 1) * pageSize).size(pageSize);
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    // ==================== 2.5 高亮 ====================

    @Test
    void testHighlight() throws IOException {
        // 1.创建Request
        SearchRequest request = new SearchRequest("items");
        // 2.组织请求参数
        // 2.1.query条件
        request.source().query(QueryBuilders.matchQuery("name", "华为"));
        // 2.2.高亮条件
        request.source().highlighter(
                SearchSourceBuilder.highlight()
                        .field("name")
                        .preTags("<em>")
                        .postTags("</em>")
        );
        // 3.发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析响应
        handleResponse(response);
    }

    /**
     * 解析响应结果，包含高亮结果的解析
     */
    private void handleResponse(SearchResponse response) {
        SearchHits searchHits = response.getHits();
        // 1.获取总条数
        long total = searchHits.getTotalHits().value;
        System.out.println("共搜索到" + total + "条数据");
        // 2.遍历结果数组
        SearchHit[] hits = searchHits.getHits();
        for (SearchHit hit : hits) {
            // 3.得到_source，也就是原始json文档
            String source = hit.getSourceAsString();
            // 4.反序列化
            ItemDoc item = JSONUtil.toBean(source, ItemDoc.class);
            // 5.获取高亮结果
            Map<String, HighlightField> hfs = hit.getHighlightFields();
            if (CollUtils.isNotEmpty(hfs)) {
                // 5.1.有高亮结果，获取name的高亮结果
                HighlightField hf = hfs.get("name");
                if (hf != null) {
                    // 5.2.获取第一个高亮结果片段，就是商品名称的高亮值
                    String hfName = hf.getFragments()[0].string();
                    item.setName(hfName);
                }
            }
            System.out.println(item);
        }
    }
}
