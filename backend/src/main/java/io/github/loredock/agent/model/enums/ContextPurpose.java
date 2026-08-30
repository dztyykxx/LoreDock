package io.github.loredock.agent.model.enums;

/**
 * 上下文组装意图：决定当前 Agent 节点允许读取的语义块与消息视图内容
 * （设计文档 §4.1 / §5 节点级最小上下文矩阵）。
 */
public enum ContextPurpose {

    /** 主 Agent 轮次基础视图：会话摘要、最近角色化轮次、当前用户指令、已确认决定与任务状态摘要。 */
    CHAT,

    /** 主 Agent 直调检索专家前的父侧组装（专家任务只通过 AgentTool actualInput 文本传递）。 */
    DIRECT_RETRIEVE,

    /** 主 Agent 直调草稿专家前的父侧组装。 */
    DIRECT_DRAFT,

    /** 主 Agent 直调审查专家前的父侧组装。 */
    DIRECT_REVIEW,

    /** 完整整理子图 Retriever 节点入口。 */
    FULL_CURATION_RETRIEVE,

    /** 完整整理子图 Coordinator（DECIDE 阶段）节点入口。 */
    FULL_CURATION_DECIDE,

    /** 完整整理子图 Coordinator（FINISH 阶段）节点入口：只输出 END 并给最终汇报。 */
    FULL_CURATION_FINISH,

    /** 完整整理子图 Drafter 节点入口；REVISE 返工时叠加本轮审查发现。 */
    FULL_CURATION_DRAFT,

    /** 完整整理子图 Reviewer 节点入口。 */
    FULL_CURATION_REVIEW,

    /** 主 Agent 子图完成后的最终汇总入口（只输出 TURN_DONE）。 */
    FULL_CURATION_REPORT,

    /** 结构化结果修复回路入口：最小输入 + 有界错误摘要 + lastValidatedNode。 */
    REPAIR;
}
