package com.hmall.controller;

import com.hmall.api.client.ItemClient;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ItemClient itemClient;

    @ApiOperation("搜索商品")
    @GetMapping("/list")
    public Map<String, Object> search(@RequestParam(value = "key", required = false) String key,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "brand", required = false) String brand,
                                      @RequestParam(value = "minPrice", required = false) Integer minPrice,
                                      @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
                                      @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                      @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                      @RequestParam(value = "sortBy", required = false) String sortBy,
                                      @RequestParam(value = "isAsc", defaultValue = "false") Boolean isAsc) {
        Map<String, Object> query = new HashMap<>();
        query.put("key", key);
        query.put("category", category);
        query.put("brand", brand);
        query.put("minPrice", minPrice);
        query.put("maxPrice", maxPrice);
        query.put("pageNo", pageNo);
        query.put("pageSize", pageSize);
        query.put("sortBy", sortBy);
        query.put("isAsc", isAsc);
        return itemClient.searchItems(query);
    }
}
