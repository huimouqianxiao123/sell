package com.example.sell.ai.search;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 中文分词与关键词提取（基于 HanLP）
 * <p>
 * 提供分词和关键词提取两种能力，统一过滤停用词和噪音词条。
 *
 * @author 屈轩
 */
@Slf4j
@Component
public class ChineseTokenizer {

    /** 最小词条长度，过滤单字符噪音 */
    private static final int MIN_TERM_LENGTH = 2;

    /** 中文停用词表（涵盖助词、介词、连词、代词、语气词等常见虚词） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "他",
            "她", "它", "们", "吗", "什么", "怎么", "哪", "能", "吧", "请",
            "想", "可以", "还是", "还", "啊", "呢", "嗯", "呀", "哦", "哈",
            "被", "把", "让", "给", "比", "跟", "从", "对", "向", "与",
            "而", "但", "因为", "所以", "如果", "虽然", "但是", "而且",
            "或者", "以及", "关于", "通过", "进行", "已经", "可能",
            "这个", "那个", "这些", "那些", "这样", "那样",
            "非常", "特别", "其实", "只是", "一下", "一些"
    );

    @PostConstruct
    void warmUp() {
        // 预热 HanLP 词典，避免首次请求时的加载延迟
        HanLP.segment("预热");
        log.info("[ChineseTokenizer] HanLP 词典预热完成");
    }

    /**
     * 分词：返回过滤后的词条列表
     * <p>
     * 过滤规则：去停用词、去标点、去单字符、去纯数字
     *
     * @param text 待分词文本
     * @return 过滤后的词条列表（不可变）
     */
    public List<String> tokenize(String text) {
        // 空值校验
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 调用 HanLP 进行中文分词
        List<Term> terms = HanLP.segment(text);
        // 对流式结果执行多级过滤
        List<String> filtered = terms.stream()
                .map(t -> t.word.trim())                        // 去除首尾空白
                .filter(w -> w.length() >= MIN_TERM_LENGTH)     // 过滤单字符噪音（长度 < 2）
                .filter(w -> !STOP_WORDS.contains(w))           // 过滤停用词
                // 过滤标点符号（Unicode 标点类 + 空白字符）
                .filter(w -> !w.matches("[\\p{Punct}\\p{IsPunctuation}\\s]+"))
                // 过滤纯数字词条（如 "12345" 对搜索意义不大）
                .filter(w -> !w.matches("\\d+"))
                .toList();

        // 返回不可变列表，防止外部修改
        return List.copyOf(filtered);
    }

    /**
     * 关键词提取：使用 HanLP TextRank 算法提取 Top-N 关键词
     * <p>
     * 如果 TextRank 结果数量不足，回退到分词结果的去重前 N 个作为补充
     *
     * @param text 待提取文本
     * @param topN 返回关键词数量上限
     * @return 关键词列表（不可变）
     */
    public List<String> extractKeywords(String text, int topN) {
        // 空值校验
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 第一步：HanLP TextRank 提取（按权重排序）
        List<String> keywords = HanLP.extractKeyword(text, topN).stream()
                .filter(w -> w.length() >= MIN_TERM_LENGTH)     // 过滤单字符
                .filter(w -> !STOP_WORDS.contains(w))           // 过滤停用词
                .toList();

        // 第二步：TextRank 结果不足时，用普通分词结果回退补充
        if (keywords.size() < topN) {
            List<String> fallback = tokenize(text).stream()
                    .distinct()                                     // 去重
                    .filter(w -> !keywords.contains(w))             // 排除已选关键词
                    .limit((long) topN - keywords.size())           // 补齐剩余名额
                    .toList();

            // 合并：TextRank 结果在前（权重高），分词回退结果在后
            List<String> combined = new java.util.ArrayList<>(keywords);
            combined.addAll(fallback);
            return List.copyOf(combined);
        }

        // TextRank 结果已满足 topN 数量，直接返回
        return keywords;
    }
}
