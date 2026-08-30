package io.github.loredock.agent.model.context;

/**
 * 会话摘要的 Checkpoint 状态（设计文档 §7.2）：随父 Graph Checkpoint 持久化的五个 REPLACE 键。
 *
 * <p>摘要属于可重建的派生缓存（不新增独立摘要表）；{@code summarySourceDigest} 只计算截至
 * {@code summaryThroughMessageId} 的已过滤业务消息，{@code summarySchemaVersion} 与压缩 Agent
 * 定义版本联动，任一不匹配即失效并从业务消息表重建。</p>
 */
public record ContextSummaryState(
        String conversationSummary,
        long summaryThroughMessageId,
        String summarySourceDigest,
        String summarySchemaVersion,
        int summaryGeneration
) {

    /** 当前摘要 Schema 版本：压缩规则或摘要结构变化时递增使旧摘要失效。 */
    public static final String SCHEMA_VERSION = "v1";

    /** @return 是否存在可复用（未失效校验由 digest/schema 比对决定）的摘要状态 */
    public boolean hasSummary() {
        return conversationSummary != null && !conversationSummary.isBlank();
    }
}
