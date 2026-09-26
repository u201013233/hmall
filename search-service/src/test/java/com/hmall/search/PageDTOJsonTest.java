package com.hmall.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.config.JsonConfig;
import com.hmall.common.domain.PageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页结果序列化测试。
 * <p>
 * hm-common 的 JsonConfig 会把 Long 序列化成字符串（避免雪花id在JS中丢失精度），
 * 而前端是用 total 参与算术运算计算总页数的：total + pageSize - 1。
 * 如果 total 是字符串，JS会先做字符串拼接（"10022" + 20 = "1002220"），页数就算错了。
 * 因此 PageDTO 的 total、pages 必须是基本类型 long，序列化成数字。
 */
public class PageDTOJsonTest {

    @Test
    void testPageDTOFieldsAreNumbers() throws Exception {
        // 按项目的Jackson配置（含hm-common的JsonConfig）构建ObjectMapper
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(new PageDTO<>(10022L, 502L, Collections.emptyList()));
        System.out.println("分页结果序列化为: " + json);

        assertTrue(json.contains("\"total\":10022"), "total必须是数字，实际: " + json);
        assertTrue(json.contains("\"pages\":502"), "pages必须是数字，实际: " + json);
    }
}
