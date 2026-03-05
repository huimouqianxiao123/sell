package com.example.sell.ai.tool;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 商品查询工具
 * 由 ReactAgent 在需要时自动调用，执行安全的参数化 SELECT 查询
 */
@Slf4j
@Component
public class ProductQueryTool implements Function<ProductQueryRequest, String> {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public String apply(ProductQueryRequest request) {
        log.info("[AI工具] ========== 商品查询工具被调用 ==========");
        log.info("[AI工具] 原始请求参数: {}", JSONUtil.toJsonStr(request));
        log.info("[AI工具] 参数明细: nameKeyword={}, minPrice={}, maxPrice={}, onSaleOnly={}, limit={}",
                request.getNameKeyword(), request.getMinPrice(), request.getMaxPrice(),
                request.getOnSaleOnly(), request.getLimit());

        // 只允许 SELECT，基础表字段不暴露敏感字段（如 version）
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, price, stock, description, image, status " +
                "FROM product WHERE 1=1"
        );
        List<Object> params = new ArrayList<>();

        // 按名称模糊匹配
        if (StringUtils.hasText(request.getNameKeyword())) {
            sql.append(" AND name LIKE ?");
            params.add("%" + request.getNameKeyword() + "%");
        }

        // 最低价格
        if (request.getMinPrice() != null) {
            sql.append(" AND price >= ?");
            params.add(request.getMinPrice());
        }

        // 最高价格
        if (request.getMaxPrice() != null) {
            sql.append(" AND price <= ?");
            params.add(request.getMaxPrice());
        }

        // 只查在售商品
        if (Boolean.TRUE.equals(request.getOnSaleOnly())) {
            sql.append(" AND status = 1");
        }

        // 返回数量限制，防止大量数据
        int limit = (request.getLimit() != null && request.getLimit() > 0)
                ? Math.min(request.getLimit(), 20)
                : 10;
        sql.append(" LIMIT ").append(limit);

        log.info("[AI工具] 执行SQL: {}", sql);
        log.info("[AI工具] SQL参数: {}", params);

        try {
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    sql.toString(), params.toArray()
            );
            long elapsed = System.currentTimeMillis() - startTime;

            if (results.isEmpty()) {
                log.info("[AI工具] 查询结果为空，耗时 {}ms", elapsed);
                return "未找到符合条件的商品，请尝试放宽搜索条件。";
            }
            String resultJson = JSONUtil.toJsonStr(results);
            log.info("[AI工具] 查询到 {} 条商品，耗时 {}ms", results.size(), elapsed);
            log.info("[AI工具] 查询结果: {}", resultJson.length() > 1000 ? resultJson.substring(0, 1000) + "...(截断)" : resultJson);
            log.info("[AI工具] ========== 商品查询工具执行完毕 ==========");
            return resultJson;
        } catch (Exception e) {
            log.error("[AI工具] 商品查询执行失败, SQL={}, 参数={}", sql, params, e);
            return "查询失败，请稍后重试。";
        }
    }
}
