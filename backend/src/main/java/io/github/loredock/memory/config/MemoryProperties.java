package io.github.loredock.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 记忆模块行为边界配置：检索有界化、写入预算与判断召回上限。
 *
 * <p>所有上限必须大于零；摘要限长不得大于正文限长、兜底限长不得大于预载上限，
 * 配置非法在启动时直接抛错终止，不猜测回退值。</p>
 *
 * @param preloadLimit 摘要预载硬上限（默认 30）
 * @param fallbackLimit 无命中时高频兜底上限（默认 3）
 * @param summaryMaxLength 摘要注入限长（码点，默认 300）
 * @param titleMaxLength 标题限长（码点，默认 200）
 * @param contentMaxLength 正文限长（码点，默认 4000）
 * @param candidateLimit 单次 memory_write 候选上限（默认 3）
 * @param writeBudgetPerRun 单 run 累计新写上限（默认 10）；达到后本 run 拒写
 * @param nearDuplicateRecallLimit 判断链相近既有记忆召回上限（默认 50）
 */
@Validated
@ConfigurationProperties("loredock.memory")
public record MemoryProperties(
        @DefaultValue("30") int preloadLimit,
        @DefaultValue("3") int fallbackLimit,
        @DefaultValue("300") int summaryMaxLength,
        @DefaultValue("200") int titleMaxLength,
        @DefaultValue("4000") int contentMaxLength,
        @DefaultValue("3") int candidateLimit,
        @DefaultValue("10") int writeBudgetPerRun,
        @DefaultValue("50") int nearDuplicateRecallLimit
) {

    public MemoryProperties {
        if (preloadLimit < 1) {
            throw new IllegalArgumentException("记忆预载上限必须大于零");
        }
        if (fallbackLimit < 1 || fallbackLimit > preloadLimit) {
            throw new IllegalArgumentException("记忆兜底上限必须在 1~预载上限 之间");
        }
        if (summaryMaxLength < 1 || titleMaxLength < 1 || contentMaxLength < 1) {
            throw new IllegalArgumentException("标题/摘要/正文限长必须大于零");
        }
        if (contentMaxLength < summaryMaxLength) {
            throw new IllegalArgumentException("正文限长不能小于摘要限长");
        }
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("单次写入候选上限必须大于零");
        }
        if (writeBudgetPerRun < 1) {
            throw new IllegalArgumentException("单 run 写入预算必须大于零");
        }
        if (nearDuplicateRecallLimit < 1 || nearDuplicateRecallLimit > 200) {
            throw new IllegalArgumentException("相近记忆召回上限必须在 1~200 之间");
        }
    }
}
