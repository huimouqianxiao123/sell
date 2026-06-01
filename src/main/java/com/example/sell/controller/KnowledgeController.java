package com.example.sell.controller;

import com.example.sell.ai.search.ElasticAiSearchService;
import com.example.sell.common.R;
import com.example.sell.service.impl.KnowledgeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * @author 屈轩
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final ElasticAiSearchService elasticAiSearchService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               ElasticAiSearchService elasticAiSearchService) {
        this.knowledgeService = knowledgeService;
        this.elasticAiSearchService = elasticAiSearchService;
    }

    /**
     * 上传pdf文档,word文档
     * @return
     */
    @PostMapping("/upload")
    public R<String> upload(@RequestParam MultipartFile file) {

        return knowledgeService.addKnowledge( file);
    }

    /**
     * 从 MySQL 重建 ES 关键词索引。
     */
    @PostMapping("/es/rebuild")
    public R<Map<String, Integer>> rebuildEsIndex() {
        return R.ok(elasticAiSearchService.rebuildFromMysql());
    }
}
