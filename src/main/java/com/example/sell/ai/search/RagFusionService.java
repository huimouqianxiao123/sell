package com.example.sell.ai.search;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Milvus 语义召回和 ES 关键词召回融合排序。
 */
@Service
public class RagFusionService {

    public List<AiSearchCandidate> mergeAndRerank(String query,
                                                  List<AiSearchCandidate> candidates,
                                                  int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<AiSearchCandidate> merged = mergeByContent(candidates);
        List<String> keywords = splitKeywords(query);
        double maxSemantic = merged.stream().mapToDouble(AiSearchCandidate::getSemanticScore).max().orElse(0.0);
        double maxKeyword = merged.stream().mapToDouble(AiSearchCandidate::getKeywordScore).max().orElse(0.0);
        long nowMs = Instant.now().toEpochMilli();

        for (AiSearchCandidate candidate : merged) {
            double semantic = normalize(candidate.getSemanticScore(), maxSemantic);
            double keyword = normalize(candidate.getKeywordScore(), maxKeyword);
            double lexical = keywordCoverage(candidate.getContent(), keywords);
            double recency = "memory".equals(candidate.getSourceType())
                    ? recencyScore(candidate.getTimestampMs(), nowMs)
                    : 0.5;

            double finalScore;
            if ("memory".equals(candidate.getSourceType())) {
                finalScore = 0.40 * keyword + 0.35 * lexical + 0.25 * recency;
            } else if ("product".equals(candidate.getSourceType())) {
                finalScore = 0.45 * keyword + 0.35 * semantic + 0.20 * lexical;
            } else {
                finalScore = 0.40 * semantic + 0.40 * keyword + 0.20 * lexical;
            }

            candidate.setLexicalScore(lexical);
            candidate.setFinalScore(clamp01(finalScore));
        }

        int safeLimit = limit <= 0 ? merged.size() : limit;
        return merged.stream()
                .sorted(Comparator.comparingDouble(AiSearchCandidate::getFinalScore).reversed())
                .limit(safeLimit)
                .toList();
    }

    private List<AiSearchCandidate> mergeByContent(List<AiSearchCandidate> candidates) {
        Map<String, AiSearchCandidate> merged = new LinkedHashMap<>();
        for (AiSearchCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getContent())) {
                continue;
            }
            String key = candidate.dedupeKey();
            AiSearchCandidate existing = merged.get(key);
            if (existing == null) {
                merged.put(key, candidate);
                continue;
            }
            existing.setSemanticScore(Math.max(existing.getSemanticScore(), candidate.getSemanticScore()));
            existing.setKeywordScore(Math.max(existing.getKeywordScore(), candidate.getKeywordScore()));
            if (!StringUtils.hasText(existing.getSource()) && StringUtils.hasText(candidate.getSource())) {
                existing.setSource(candidate.getSource());
            }
            if (!StringUtils.hasText(existing.getTitle()) && StringUtils.hasText(candidate.getTitle())) {
                existing.setTitle(candidate.getTitle());
            }
            if (existing.getTimestampMs() == null) {
                existing.setTimestampMs(candidate.getTimestampMs());
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<String> splitKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.replaceAll("[，。！？、；：,.!?;:\\s]+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of(query);
        }
        return List.of(normalized.split("\\s+"));
    }

    private double keywordCoverage(String content, List<String> keywords) {
        if (!StringUtils.hasText(content) || keywords.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && content.contains(keyword)) {
                hit++;
            }
        }
        return clamp01(hit * 1.0 / keywords.size());
    }

    private double normalize(double score, double maxScore) {
        if (score <= 0 || maxScore <= 0) {
            return 0.0;
        }
        return clamp01(score / maxScore);
    }

    private double recencyScore(Long timestampMs, long nowMs) {
        if (timestampMs == null || timestampMs <= 0) {
            return 0.0;
        }
        long ageMs = Math.max(0L, nowMs - timestampMs);
        double days = ageMs * 1.0 / (24L * 3600L * 1000L);
        return clamp01(1.0 / (1.0 + days / 7.0));
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
