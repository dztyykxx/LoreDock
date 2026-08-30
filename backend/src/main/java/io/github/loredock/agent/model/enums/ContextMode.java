package io.github.loredock.agent.model.enums;

/** 上下文组装结果模式（设计文档 §4.2）。 */
public enum ContextMode {

    /** 未超过压缩阈值，直接使用组装结果。 */
    FULL,

    /** 超过压缩阈值后经过确定性压缩，结果可复现。 */
    DETERMINISTIC,

    /** 确定性压缩后仍超限，经过受控 LLM 压缩兜底。 */
    LLM_COMPRESSED,

    /** 一切压缩手段后仍超限，调用方不得发送模型请求（run 转可恢复等待）。 */
    BLOCKED;
}
