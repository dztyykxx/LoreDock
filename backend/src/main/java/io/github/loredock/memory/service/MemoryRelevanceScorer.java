package io.github.loredock.memory.service;

import io.github.loredock.memory.model.entity.UserMemoryEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性相关性打分器（纯函数、无状态）：CJK 重叠二元组 + 空白分词；
 * 标题命中 ×3 + 摘要命中 ×2 + 正文命中 ×1 + log2(use_count + 1)；同分由调用方按
 * use_count/最近使用/编号兜底比较。同一输入永远产生同一结果，测试友好。
 */
public final class MemoryRelevanceScorer {

    private MemoryRelevanceScorer() {
    }

    /** 标题命中权重（设计：标题命中排序高于正文命中）。 */
    static final int TITLE_WEIGHT = 3;
    static final int SUMMARY_WEIGHT = 2;
    static final int CONTENT_WEIGHT = 1;

    /** @return 记忆条目得分；查询词为空时返回 0（由调用方走兜底路径） */
    public static double score(UserMemoryEntity entity, List<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        double fieldScore = 0;
        for (String term : terms) {
            if (contains(entity.getTitle(), term)) {
                fieldScore += TITLE_WEIGHT;
            }
            if (contains(entity.getSummary(), term)) {
                fieldScore += SUMMARY_WEIGHT;
            }
            if (contains(entity.getContent(), term)) {
                fieldScore += CONTENT_WEIGHT;
            }
        }
        long useCount = entity.getUseCount() == null ? 0 : entity.getUseCount();
        return fieldScore + log2(useCount + 1);
    }

    /**
     * 文本切词：连续 CJK 段产出重叠二元组（“文档格式” → 文档/档格/格式；单字段退化为该字），
     * 非 CJK 段按小写词保留（“Git-merge” → git-merge）。
     */
    public static List<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text != null) {
            for (String chunk : text.strip().split("\\s+")) {
                if (chunk.isBlank()) {
                    continue;
                }
                List<String> cjkRun = new ArrayList<>();
                StringBuilder latin = new StringBuilder();
                for (int index = 0; index < chunk.length(); ) {
                    int codePoint = chunk.codePointAt(index);
                    index += Character.charCount(codePoint);
                    if (isCjk(codePoint)) {
                        flushLatin(terms, latin);
                        cjkRun.add(new String(Character.toChars(codePoint)));
                    } else {
                        flourishCjk(terms, cjkRun);
                        latin.appendCodePoint(codePoint);
                    }
                }
                flourishCjk(terms, cjkRun);
                flushLatin(terms, latin);
            }
        }
        return List.copyOf(terms);
    }

    /** 包含判定：CJK 二元组按码点序列精确包含；ASCII 词大小写不敏感包含。 */
    private static boolean contains(String text, String term) {
        if (text == null || text.isBlank() || term == null || term.isBlank()) {
            return false;
        }
        if (text.contains(term)) {
            return true;
        }
        return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }

    private static void flushLatin(Set<String> terms, StringBuilder latin) {
        if (latin.length() > 0) {
            terms.add(latin.toString().toLowerCase(Locale.ROOT));
            latin.setLength(0);
        }
    }

    private static void flourishCjk(Set<String> terms, List<String> run) {
        if (run.size() == 1) {
            terms.add(run.get(0));
        } else if (run.size() >= 2) {
            for (int index = 0; index + 1 < run.size(); index++) {
                terms.add(run.get(index) + run.get(index + 1));
            }
        }
        run.clear();
    }

    private static double log2(long value) {
        return value <= 1 ? 0 : Math.log(value) / Math.log(2);
    }
}
