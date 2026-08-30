package io.github.loredock.agent.model.enums;

/**
 * 知识整理 Graph 中需要上下文组装的 Agent 节点。
 *
 * <p>对应设计文档 §4.1：每个 Agent 节点入口按当前 Agent 重新组装最小语义上下文。</p>
 */
public enum AgentNode {

    /** 会话级主 Agent：唯一对话口径，持有三个专家 AgentTool。 */
    MAIN_AGENT,

    /** 检索 Agent；只读取候选与现有知识。 */
    RETRIEVER,

    /** 调度 Agent（Coordinator）；只输出结构化决策。 */
    COORDINATOR,

    /** 草稿 Agent；按已支持事实写入工作草稿。 */
    DRAFTER,

    /** 审查 Agent；独立核对来源、最新草稿与 Diff。 */
    REVIEWER;

    /** @param node 框架子图节点前缀名 @return 去掉前缀后的角色名；不匹配时返回原值 */
    public static String normalize(String node) {
        return node != null && node.startsWith("subgraph_") ? node.substring("subgraph_".length()) : node;
    }
}
