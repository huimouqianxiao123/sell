package com.example.sell.ai.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFusionServiceTest {

    private final RagFusionService fusionService = new RagFusionService();

    @Test
    void shouldMergeSemanticAndElasticKeywordHitsByContent() {
        AiSearchCandidate milvusHit = AiSearchCandidate.builder()
                .sourceType("knowledge")
                .source("activity.md")
                .title("秒杀活动")
                .content("秒杀商品下单后需要在十五分钟内完成支付。")
                .timestampMs(1_700_000_000_000L)
                .semanticScore(0.76)
                .keywordScore(0.0)
                .build();
        AiSearchCandidate elasticHit = AiSearchCandidate.builder()
                .sourceType("knowledge")
                .source("activity.md")
                .title("秒杀活动")
                .content("秒杀商品下单后需要在十五分钟内完成支付。")
                .timestampMs(1_700_000_000_000L)
                .semanticScore(0.0)
                .keywordScore(8.4)
                .build();

        List<AiSearchCandidate> result = fusionService.mergeAndRerank(
                "秒杀 支付 时间", List.of(milvusHit, elasticHit), 5);

        assertEquals(1, result.size());
        assertEquals(0.76, result.get(0).getSemanticScore(), 0.0001);
        assertEquals(8.4, result.get(0).getKeywordScore(), 0.0001);
        assertTrue(result.get(0).getFinalScore() > 0.5);
    }

    @Test
    void shouldKeepElasticOnlyKeywordHitForRagRecall() {
        AiSearchCandidate semanticHit = AiSearchCandidate.builder()
                .sourceType("knowledge")
                .source("product.md")
                .title("普通商品规则")
                .content("普通商品支持加入购物车后统一结算。")
                .semanticScore(0.82)
                .build();
        AiSearchCandidate keywordHit = AiSearchCandidate.builder()
                .sourceType("knowledge")
                .source("after-sale.md")
                .title("七天无理由")
                .content("耳机拆封后如无质量问题不支持七天无理由退货。")
                .keywordScore(12.0)
                .build();

        List<AiSearchCandidate> result = fusionService.mergeAndRerank(
                "耳机 七天无理由", List.of(semanticHit, keywordHit), 5);

        assertEquals(2, result.size());
        assertEquals("七天无理由", result.get(0).getTitle());
        assertTrue(result.get(0).getKeywordScore() > 0);
    }
}
