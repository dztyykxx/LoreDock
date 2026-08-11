package io.github.loredock.knowledge.model.enums;

/**
 * 普通浏览入口类型；全局入口与项目入口不可隐式互换。
 * ALL 仅供 Agent 全局问答内部路径构造，公开浏览与检索端点必须拒绝（安全边界）。
 */
public enum KnowledgeBrowseContextType {
    GLOBAL,
    PROJECT,
    ALL
}
