package io.github.loredock.code.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次候选构建的允许文件与稳定忽略计数。
 *
 * @param selected 只含完整允许文本
 * @param ignoredCounts 按稳定原因聚合的忽略数量
 */
public record CodeFileSelectionSummary(
        List<CodeFileSelection> selected,
        Map<CodeFileIgnoreReason, Long> ignoredCounts
) {
    /** 从逐文件结果建立不可变汇总。 */
    public static CodeFileSelectionSummary from(List<CodeFileSelection> selections) {
        List<CodeFileSelection> selected = selections.stream().filter(CodeFileSelection::selected).toList();
        EnumMap<CodeFileIgnoreReason, Long> ignored = new EnumMap<>(CodeFileIgnoreReason.class);
        selections.stream().filter(selection -> !selection.selected())
                .forEach(selection -> ignored.merge(selection.ignoredReason(), 1L, Long::sum));
        return new CodeFileSelectionSummary(selected, Map.copyOf(ignored));
    }

    /** @return 全部稳定原因的忽略数量之和 */
    public long totalIgnored() {
        return ignoredCounts.values().stream().mapToLong(Long::longValue).sum();
    }
}
