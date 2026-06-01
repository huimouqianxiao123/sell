package com.example.sell.common;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 请求上下文（线程级）。
 * 保存当前请求的检索约束信息与可引用片段，供 Tool 与 Node 共享。
 */
public final class AiRequestContextHolder {

    private static final ThreadLocal<AiRequestContext> CONTEXT = new ThreadLocal<>();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private AiRequestContextHolder() {
    }

    public static void init(String userId, String sessionId, int memoryWindowDays) {
        int safeDays = memoryWindowDays <= 0 ? 30 : memoryWindowDays;
        Instant now = Instant.now();
        AiRequestContext ctx = new AiRequestContext(
                userId == null ? "" : userId,
                sessionId == null ? "" : sessionId,
                now.minusSeconds(safeDays * 24L * 3600L),
                now,
                new ArrayList<>()
        );
        CONTEXT.set(ctx);
    }

    public static AiRequestContext get() {
        return CONTEXT.get();
    }

    public static void addCitations(List<CitationSnippet> snippets) {
        AiRequestContext ctx = CONTEXT.get();
        if (ctx == null || snippets == null || snippets.isEmpty()) {
            return;
        }
        ctx.citations().addAll(snippets);
    }

    public static List<CitationSnippet> snapshotCitations(int limit) {
        AiRequestContext ctx = CONTEXT.get();
        if (ctx == null || ctx.citations().isEmpty()) {
            return Collections.emptyList();
        }
        int safeLimit = limit <= 0 ? 3 : limit;
        List<CitationSnippet> copied = new ArrayList<>();
        for (CitationSnippet item : ctx.citations()) {
            if (copied.size() >= safeLimit) {
                break;
            }
            if (!contains(copied, item)) {
                copied.add(item);
            }
        }
        return copied;
    }

    public static String renderCitationBlock(int limit) {
        List<CitationSnippet> snippets = snapshotCitations(limit);
        if (snippets.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n参考来源：\n");
        int idx = 1;
        for (CitationSnippet s : snippets) {
            sb.append(idx++)
                    .append(". [")
                    .append(s.sourceType())
                    .append("] ")
                    .append(s.source());
            if (StringUtils.hasText(s.title())) {
                sb.append(" / ").append(s.title());
            }
            if (s.timestampMs() != null && s.timestampMs() > 0) {
                sb.append(" / ").append(TIME_FORMATTER.format(Instant.ofEpochMilli(s.timestampMs())));
            }
            sb.append(" / 置信度 ").append(String.format("%.2f", clamp01(s.confidence())));
            sb.append("\n");
            sb.append("   ").append(s.snippet());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private static boolean contains(List<CitationSnippet> list, CitationSnippet item) {
        for (CitationSnippet old : list) {
            if (old.sourceType().equals(item.sourceType())
                    && old.source().equals(item.source())
                    && old.snippet().equals(item.snippet())) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record CitationSnippet(
            String sourceType,
            String source,
            String title,
            String snippet,
            Long timestampMs,
            double confidence
    ) {
    }

    public record AiRequestContext(
            String userId,
            String sessionId,
            Instant windowStart,
            Instant requestTime,
            List<CitationSnippet> citations
    ) {
    }
}
